// Screen and sound capture for the Beamline Windows sender, called from Kotlin through JNA.
//
// Video: Desktop Duplication captures one screen on the GPU, a D3D11 video processor scales the
// picture to the stream size and converts it to NV12, and the GPU's Media Foundation encoder turns
// it into H.264. Like MediaCodec on the phone, the encoder takes keyframe requests and bitrate
// changes while it runs. Without a hardware encoder, Windows' own software encoder takes over.
//
// Sound: WASAPI loopback of the default output device as 48 kHz stereo float, stamped with the time
// its first sample was played. Windows delivers nothing while nothing plays; silence fills the
// gaps, so the sound keeps going and stays on the same clock as the picture.
//
// Every timestamp is in nanoseconds on the QueryPerformanceCounter clock, see bm_now_ns. All COM
// work happens on threads of this library; the caller's threads only exchange data and flags.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

#include <audioclient.h>
#include <d3d10.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mmdeviceapi.h>
#include <strmif.h>
#include <wlanapi.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

#define BM_API extern "C" __declspec(dllexport)

namespace {

// GUIDs defined here rather than taken from the SDK headers, which differ between MinGW versions.
const GUID kForceKeyFrame = {0x398c1b98, 0x8353, 0x475a, {0x9e, 0xf2, 0x8f, 0x26, 0x5d, 0x26, 0x03, 0x45}};
const GUID kMeanBitRate = {0xf7222374, 0x2144, 0x4815, {0xb5, 0x50, 0xa3, 0x7f, 0x8e, 0x12, 0xee, 0x52}};
const GUID kRateControlMode = {0x1c0608e9, 0x370c, 0x4710, {0x8a, 0x58, 0xcb, 0x61, 0x81, 0xc4, 0x24, 0x23}};
const GUID kLowLatencyMode = {0x9c27891a, 0xed7a, 0x40e1, {0x88, 0xe8, 0xb2, 0x27, 0x27, 0xa0, 0x24, 0xee}};
const GUID kGopSize = {0x95f31b26, 0x95a4, 0x41aa, {0x93, 0x03, 0x24, 0x6a, 0x7f, 0xc6, 0xee, 0xf1}};
const GUID kBPictureCount = {0x8d390aac, 0xdc5c, 0x4200, {0xb5, 0x7f, 0x81, 0x4d, 0x04, 0xba, 0xba, 0xb2}};
const GUID kAdapterLuid = {0x1d39518c, 0xe220, 0x4da8, {0xa0, 0x7f, 0xba, 0x17, 0x25, 0x52, 0xd6, 0xb1}};
const GUID kAsync = {0xf81a699a, 0x649a, 0x497d, {0x8c, 0x73, 0x29, 0xf8, 0xfe, 0xd6, 0xad, 0x7a}};
const GUID kAsyncUnlock = {0xe5666d6b, 0x3422, 0x4eb6, {0xa4, 0x21, 0xda, 0x7d, 0xb1, 0xf8, 0xe2, 0x07}};
const GUID kFriendlyName = {0x314ffbae, 0x5b41, 0x4c95, {0x9c, 0x19, 0x4e, 0x7d, 0x58, 0x6f, 0xac, 0xe3}};
const GUID kD3D11Aware = {0x206b4fc8, 0xfcf9, 0x4c51, {0xaf, 0xe3, 0x97, 0x64, 0x36, 0x9e, 0x33, 0xa0}};
const GUID kFloatSubtype = {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const GUID kPcmSubtype = {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71}};
const IID kCodecApi = {0x901db4c7, 0x31ce, 0x41a2, {0x85, 0xdc, 0x8f, 0xa0, 0xbf, 0x41, 0xb8, 0xda}};

constexpr UINT32 kRateControlCbr = 0;
constexpr UINT32 kProfileHigh = 100;
constexpr UINT32 kProfileMain = 77;

// NV12 pictures the encoder may still be reading; at most kMaxInFlight are handed to it at once.
constexpr int kRing = 6;
constexpr int kMaxInFlight = 4;

// A still screen sends its last picture again this often, as the phone does: the TV keeps getting
// data, and the encoder sharpens what it sent after a change.
constexpr int64_t kRepeatAfterNs = 100'000'000;

// While Windows doesn't let the screen be captured (a resolution change, the secure desktop of a
// UAC prompt), the capture tries again this often, and gives up after kReopenGiveUpNs.
constexpr int64_t kReopenEveryNs = 200'000'000;
constexpr int64_t kReopenGiveUpNs = 30'000'000'000;

constexpr int kSampleRate = 48'000;
constexpr int kChannels = 2;

// Sound more than this late counts as a gap that silence fills; less is the device's own jitter.
constexpr int64_t kAudioJitterNs = 2'000'000;

// While nothing plays, silence is added up to this far behind the clock, leaving room for sound
// that is just starting to come in.
constexpr int64_t kSilenceLagNs = 20'000'000;

// Sound nobody reads is thrown away beyond this.
constexpr size_t kMaxQueuedAudioFrames = 2 * kSampleRate;

// Encoded frames nobody reads are thrown away beyond this.
constexpr size_t kMaxQueuedPackets = 120;

template <typename T>
class Com {
 public:
  Com() = default;
  Com(const Com&) = delete;
  Com& operator=(const Com&) = delete;
  ~Com() { reset(); }

  T* get() const { return p_; }
  T* operator->() const { return p_; }
  explicit operator bool() const { return p_ != nullptr; }

  T** put() {
    reset();
    return &p_;
  }

  void** putVoid() { return reinterpret_cast<void**>(put()); }

  void reset() {
    if (p_) {
      p_->Release();
      p_ = nullptr;
    }
  }

  template <typename U>
  HRESULT as(Com<U>& out) const {
    return p_ ? p_->QueryInterface(__uuidof(U), out.putVoid()) : E_POINTER;
  }

 private:
  T* p_ = nullptr;
};

class Locked {
 public:
  explicit Locked(CRITICAL_SECTION& section) : section_(section) { EnterCriticalSection(&section_); }
  ~Locked() { LeaveCriticalSection(&section_); }
  Locked(const Locked&) = delete;
  Locked& operator=(const Locked&) = delete;

 private:
  CRITICAL_SECTION& section_;
};

thread_local std::string lastError;

std::string failure(const char* what, HRESULT hr) {
  char text[200];
  std::snprintf(text, sizeof text, "%s failed (0x%08lX)", what, static_cast<unsigned long>(hr));
  return text;
}

std::string utf8(const wchar_t* text) {
  if (!text || !*text) return {};
  int size = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
  std::string out(size > 0 ? size - 1 : 0, '\0');
  if (size > 1) WideCharToMultiByte(CP_UTF8, 0, text, -1, out.data(), size, nullptr, nullptr);
  return out;
}

int64_t qpcFrequency() {
  static const int64_t frequency = [] {
    LARGE_INTEGER value;
    QueryPerformanceFrequency(&value);
    return value.QuadPart;
  }();
  return frequency;
}

int64_t qpcToNs(int64_t ticks) {
  const int64_t f = qpcFrequency();
  return ticks / f * 1'000'000'000 + ticks % f * 1'000'000'000 / f;
}

int64_t nowNs() {
  LARGE_INTEGER value;
  QueryPerformanceCounter(&value);
  return qpcToNs(value.QuadPart);
}

class ComApartment {
 public:
  ComApartment() : ok_(SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) {}
  ~ComApartment() {
    if (ok_) CoUninitialize();
  }
  ComApartment(const ComApartment&) = delete;
  ComApartment& operator=(const ComApartment&) = delete;

 private:
  bool ok_;
};

// ---------------------------------------------------------------------------------------------
// Screens

struct OutputRef {
  UINT adapter;
  UINT output;
  DXGI_OUTPUT_DESC desc;
};

bool isPrimary(const DXGI_OUTPUT_DESC& desc) {
  return desc.DesktopCoordinates.left == 0 && desc.DesktopCoordinates.top == 0;
}

// The screens on the desktop, the primary one first.
std::vector<OutputRef> enumerateOutputs() {
  std::vector<OutputRef> list;
  Com<IDXGIFactory1> factory;
  if (FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1), factory.putVoid()))) return list;
  Com<IDXGIAdapter1> adapter;
  for (UINT a = 0; factory->EnumAdapters1(a, adapter.put()) != DXGI_ERROR_NOT_FOUND; a++) {
    Com<IDXGIOutput> output;
    for (UINT o = 0; adapter->EnumOutputs(o, output.put()) != DXGI_ERROR_NOT_FOUND; o++) {
      DXGI_OUTPUT_DESC desc;
      if (SUCCEEDED(output->GetDesc(&desc)) && desc.AttachedToDesktop) list.push_back({a, o, desc});
    }
  }
  std::stable_sort(list.begin(), list.end(),
                   [](const OutputRef& a, const OutputRef& b) { return isPrimary(a.desc) && !isPrimary(b.desc); });
  return list;
}

// ---------------------------------------------------------------------------------------------
// Video

struct Packet {
  std::vector<uint8_t> data;
  int64_t ptsNs;
  int flags;
};

constexpr int kPacketKeyframe = 1;

struct Video {
  int screen = 0;
  int width = 0;
  int height = 0;
  int fps = 30;
  std::atomic<int> bitrate{0};
  std::atomic<bool> keyframeWanted{false};
  std::atomic<bool> running{true};

  // Touched by the capture thread, and by the event thread only where noted.
  Com<IDXGIAdapter1> adapter;
  Com<IDXGIOutput1> output;
  Com<ID3D11Device> device;
  Com<ID3D11DeviceContext> context;
  Com<ID3D11VideoDevice> videoDevice;
  Com<ID3D11VideoContext> videoContext;
  Com<IDXGIOutputDuplication> duplication;
  Com<ID3D11Texture2D> desktop;
  Com<ID3D11VideoProcessorEnumerator> processorEnum;
  Com<ID3D11VideoProcessor> processor;
  Com<ID3D11VideoProcessorInputView> inputView;
  Com<ID3D11Texture2D> ring[kRing];
  Com<ID3D11VideoProcessorOutputView> ringViews[kRing];
  Com<ID3D11Texture2D> staging;
  int nextSlot = 0;
  UINT sourceWidth = 0;
  UINT sourceHeight = 0;
  Com<IMFDXGIDeviceManager> deviceManager;
  UINT resetToken = 0;
  int appliedBitrate = 0;

  // The encoder; calls into it are serialised by encoderLock. The event thread uses it too.
  Com<IMFActivate> activate;
  Com<IMFTransform> encoder;
  Com<ICodecAPI> codecApi;
  Com<IMFMediaEventGenerator> events;
  SRWLOCK encoderLock = SRWLOCK_INIT;
  bool async = false;
  bool hardware = false;
  bool providesSamples = false;
  DWORD outputSize = 0;
  std::string encoderName;
  std::vector<uint8_t> sequenceHeader;

  // Shared, guarded by lock.
  CRITICAL_SECTION lock;
  CONDITION_VARIABLE changed;
  int needInput = 0;
  int inFlight = 0;
  std::deque<Packet> packets;
  std::string error;
  bool failed = false;
  bool sourceChanged = false;
  bool ready = false;

  HANDLE captureThread = nullptr;
  HANDLE eventThread = nullptr;

  Video() {
    InitializeCriticalSection(&lock);
    InitializeConditionVariable(&changed);
  }

  ~Video() { DeleteCriticalSection(&lock); }
};

class EncoderLocked {
 public:
  explicit EncoderLocked(Video& v) : v_(v) { AcquireSRWLockExclusive(&v_.encoderLock); }
  ~EncoderLocked() { ReleaseSRWLockExclusive(&v_.encoderLock); }
  EncoderLocked(const EncoderLocked&) = delete;
  EncoderLocked& operator=(const EncoderLocked&) = delete;

 private:
  Video& v_;
};

bool fail(Video& v, const std::string& message) {
  Locked l(v.lock);
  if (!v.failed) {
    v.failed = true;
    v.error = message;
  }
  WakeAllConditionVariable(&v.changed);
  return false;
}

bool hasNal(const uint8_t* data, size_t size, int type) {
  for (size_t i = 0; i + 3 < size; i++) {
    if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0x1F) == type) return true;
  }
  return false;
}

void readSequenceHeader(Video& v) {
  EncoderLocked l(v);
  Com<IMFMediaType> type;
  if (FAILED(v.encoder->GetOutputCurrentType(0, type.put()))) return;
  UINT32 size = 0;
  if (FAILED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &size)) || size == 0) return;
  std::vector<uint8_t> header(size);
  if (SUCCEEDED(type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, header.data(), size, nullptr))) v.sequenceHeader = header;
}

void deliver(Video& v, IMFSample* sample) {
  LONGLONG time = 0;
  sample->GetSampleTime(&time);
  UINT32 cleanPoint = 0;
  sample->GetUINT32(MFSampleExtension_CleanPoint, &cleanPoint);
  Com<IMFMediaBuffer> buffer;
  if (FAILED(sample->ConvertToContiguousBuffer(buffer.put()))) return;
  BYTE* bytes = nullptr;
  DWORD length = 0;
  if (FAILED(buffer->Lock(&bytes, nullptr, &length))) return;
  Packet packet;
  packet.ptsNs = time * 100;
  packet.flags = cleanPoint ? kPacketKeyframe : 0;
  // Some encoders keep the SPS and PPS in the media type instead of in front of each keyframe.
  if (cleanPoint && !hasNal(bytes, length, 7)) {
    if (v.sequenceHeader.empty()) readSequenceHeader(v);
    packet.data = v.sequenceHeader;
  }
  packet.data.insert(packet.data.end(), bytes, bytes + length);
  buffer->Unlock();
  Locked l(v.lock);
  if (v.packets.size() >= kMaxQueuedPackets) v.packets.pop_front();
  v.packets.push_back(std::move(packet));
  WakeAllConditionVariable(&v.changed);
}

// Takes one encoded frame from the encoder. Returns 1 when one came out, 0 when there was none,
// and -1 when the encoder failed.
int takeOutput(Video& v) {
  MFT_OUTPUT_DATA_BUFFER out = {};
  Com<IMFSample> own;
  Com<IMFMediaBuffer> ownBuffer;
  if (!v.providesSamples) {
    if (FAILED(MFCreateSample(own.put())) || FAILED(MFCreateMemoryBuffer(v.outputSize, ownBuffer.put()))) {
      fail(v, "Allocating an output buffer failed");
      return -1;
    }
    own->AddBuffer(ownBuffer.get());
    out.pSample = own.get();
  }
  DWORD status = 0;
  HRESULT hr;
  {
    EncoderLocked l(v);
    hr = v.encoder->ProcessOutput(0, 1, &out, &status);
  }
  if (out.pEvents) out.pEvents->Release();
  if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) return 0;
  if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
    // The encoder settled the details of its output; take them as offered.
    EncoderLocked l(v);
    Com<IMFMediaType> type;
    if (SUCCEEDED(v.encoder->GetOutputAvailableType(0, 0, type.put()))) v.encoder->SetOutputType(0, type.get(), 0);
    MFT_OUTPUT_STREAM_INFO info = {};
    if (SUCCEEDED(v.encoder->GetOutputStreamInfo(0, &info)) && info.cbSize > v.outputSize) v.outputSize = info.cbSize;
    v.sequenceHeader.clear();
    return 0;
  }
  if (FAILED(hr)) {
    fail(v, failure("Encoding", hr));
    return -1;
  }
  if (out.pSample) {
    deliver(v, out.pSample);
    if (v.providesSamples) out.pSample->Release();
  }
  return 1;
}

DWORD WINAPI eventMain(void* argument) {
  Video& v = *static_cast<Video*>(argument);
  ComApartment apartment;
  for (;;) {
    Com<IMFMediaEvent> event;
    HRESULT hr = v.events->GetEvent(0, event.put());
    if (FAILED(hr)) {
      if (v.running) fail(v, failure("Waiting for the encoder", hr));
      break;
    }
    MediaEventType type = MEUnknown;
    event->GetType(&type);
    HRESULT status = S_OK;
    event->GetStatus(&status);
    if (type == METransformNeedInput) {
      Locked l(v.lock);
      v.needInput++;
      WakeAllConditionVariable(&v.changed);
    } else if (type == METransformHaveOutput) {
      int taken = takeOutput(v);
      if (taken < 0) break;
      if (taken > 0) {
        Locked l(v.lock);
        if (v.inFlight > 0) v.inFlight--;
        WakeAllConditionVariable(&v.changed);
      }
    } else if (type == METransformDrainComplete) {
      break;
    } else if (type == MEError || FAILED(status)) {
      fail(v, failure("The encoder", FAILED(status) ? status : E_FAIL));
      break;
    }
  }
  return 0;
}

void setCodecValue(ICodecAPI* api, const GUID& property, UINT32 value) {
  VARIANT variant;
  VariantInit(&variant);
  variant.vt = VT_UI4;
  variant.ulVal = value;
  api->SetValue(&property, &variant);
}

void setCodecFlag(ICodecAPI* api, const GUID& property) {
  VARIANT variant;
  VariantInit(&variant);
  variant.vt = VT_BOOL;
  variant.boolVal = VARIANT_TRUE;
  api->SetValue(&property, &variant);
}

// Settings for a screen: a steady rate, every frame out at once, no B-frames, and keyframes only
// on request or every ten seconds.
void configureCodec(Video& v) {
  if (!v.codecApi) return;
  setCodecValue(v.codecApi.get(), kRateControlMode, kRateControlCbr);
  setCodecValue(v.codecApi.get(), kMeanBitRate, v.bitrate.load());
  setCodecFlag(v.codecApi.get(), kLowLatencyMode);
  setCodecValue(v.codecApi.get(), kGopSize, v.fps * 10);
  setCodecValue(v.codecApi.get(), kBPictureCount, 0);
  v.appliedBitrate = v.bitrate.load();
}

void setVideoAttributes(IMFMediaType* type, const GUID& subtype, const Video& v) {
  type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
  type->SetGUID(MF_MT_SUBTYPE, subtype);
  MFSetAttributeSize(type, MF_MT_FRAME_SIZE, v.width, v.height);
  MFSetAttributeRatio(type, MF_MT_FRAME_RATE, v.fps, 1);
  MFSetAttributeRatio(type, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
  type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
  // HD video colours in TV range, as the video processor writes them.
  type->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
  type->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709);
  type->SetUINT32(MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709);
  type->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
}

bool setTypes(Video& v, IMFTransform* mft) {
  for (UINT32 profile : {kProfileHigh, kProfileMain}) {
    Com<IMFMediaType> out;
    if (FAILED(MFCreateMediaType(out.put()))) return false;
    setVideoAttributes(out.get(), MFVideoFormat_H264, v);
    out->SetUINT32(MF_MT_AVG_BITRATE, v.bitrate.load());
    out->SetUINT32(MF_MT_MPEG2_PROFILE, profile);
    if (FAILED(mft->SetOutputType(0, out.get(), 0))) continue;
    Com<IMFMediaType> in;
    for (DWORD i = 0; SUCCEEDED(mft->GetInputAvailableType(0, i, in.put())); i++) {
      GUID subtype = GUID_NULL;
      if (SUCCEEDED(in->GetGUID(MF_MT_SUBTYPE, &subtype)) && subtype == MFVideoFormat_NV12) break;
      in.reset();
    }
    if (!in && FAILED(MFCreateMediaType(in.put()))) return false;
    setVideoAttributes(in.get(), MFVideoFormat_NV12, v);
    if (SUCCEEDED(mft->SetInputType(0, in.get(), 0))) return true;
  }
  return false;
}

bool tryEncoder(Video& v, IMFActivate* activate, bool hardware) {
  Com<IMFTransform> mft;
  if (FAILED(activate->ActivateObject(__uuidof(IMFTransform), mft.putVoid()))) return false;
  bool ok = false;
  do {
    Com<IMFAttributes> attributes;
    UINT32 isAsync = 0;
    if (SUCCEEDED(mft->GetAttributes(attributes.put()))) {
      attributes->GetUINT32(kAsync, &isAsync);
      if (isAsync) attributes->SetUINT32(kAsyncUnlock, TRUE);
    }
    if (hardware) {
      UINT32 aware = 0;
      if (!attributes || FAILED(attributes->GetUINT32(kD3D11Aware, &aware)) || !aware) break;
      if (!v.deviceManager) {
        if (FAILED(MFCreateDXGIDeviceManager(&v.resetToken, v.deviceManager.put()))) break;
        if (FAILED(v.deviceManager->ResetDevice(v.device.get(), v.resetToken))) {
          v.deviceManager.reset();
          break;
        }
      }
      if (FAILED(mft->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER, reinterpret_cast<ULONG_PTR>(v.deviceManager.get())))) break;
    } else if (isAsync) {
      break;
    }
    if (FAILED(mft->QueryInterface(kCodecApi, v.codecApi.putVoid()))) v.codecApi.reset();
    configureCodec(v);
    if (!setTypes(v, mft.get())) break;
    // Some encoders take the rate only once the types are set.
    configureCodec(v);
    MFT_OUTPUT_STREAM_INFO info = {};
    if (FAILED(mft->GetOutputStreamInfo(0, &info))) break;
    v.providesSamples = info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES);
    v.outputSize = std::max<DWORD>(info.cbSize, v.width * v.height * 3 / 2);
    if (isAsync && FAILED(mft.as(v.events))) break;
    mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    wchar_t* name = nullptr;
    UINT32 nameLength = 0;
    if (SUCCEEDED(activate->GetAllocatedString(kFriendlyName, &name, &nameLength))) {
      v.encoderName = utf8(name);
      CoTaskMemFree(name);
    }
    if (v.encoderName.empty()) v.encoderName = hardware ? "Hardware H.264 encoder" : "Software H.264 encoder";
    v.async = isAsync;
    v.hardware = hardware;
    v.encoder.reset();
    mft.as(v.encoder);
    ok = true;
  } while (false);
  if (!ok) {
    v.codecApi.reset();
    v.events.reset();
    activate->ShutdownObject();
  }
  return ok;
}

bool sameAdapter(IMFActivate* activate, const LUID& luid) {
  LUID found = {};
  UINT32 size = 0;
  if (FAILED(activate->GetBlob(kAdapterLuid, reinterpret_cast<UINT8*>(&found), sizeof found, &size))) return true;
  return size == sizeof found && found.LowPart == luid.LowPart && found.HighPart == luid.HighPart;
}

// The GPU's own encoder on the adapter showing the screen, or else Windows' software encoder.
bool setupEncoder(Video& v) {
  DXGI_ADAPTER_DESC1 adapterDesc = {};
  v.adapter->GetDesc1(&adapterDesc);
  MFT_REGISTER_TYPE_INFO input = {MFMediaType_Video, MFVideoFormat_NV12};
  MFT_REGISTER_TYPE_INFO output = {MFMediaType_Video, MFVideoFormat_H264};
  for (bool hardware : {true, false}) {
    IMFActivate** list = nullptr;
    UINT32 count = 0;
    UINT32 flags = (hardware ? MFT_ENUM_FLAG_HARDWARE : MFT_ENUM_FLAG_SYNCMFT) | MFT_ENUM_FLAG_SORTANDFILTER;
    if (FAILED(MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, flags, &input, &output, &list, &count))) continue;
    bool found = false;
    for (UINT32 i = 0; i < count; i++) {
      if (!found && (!hardware || sameAdapter(list[i], adapterDesc.AdapterLuid)) && tryEncoder(v, list[i], hardware)) {
        found = true;
        list[i]->AddRef();
        v.activate.reset();
        *v.activate.put() = list[i];
      }
      list[i]->Release();
    }
    CoTaskMemFree(list);
    if (found) return true;
  }
  return fail(v, "No H.264 encoder found");
}

bool createDuplication(Video& v, HRESULT* result) {
  HRESULT hr = v.output->DuplicateOutput(v.device.get(), v.duplication.put());
  if (result) *result = hr;
  if (FAILED(hr)) return false;
  DXGI_OUTDUPL_DESC desc;
  v.duplication->GetDesc(&desc);
  v.sourceWidth = desc.ModeDesc.Width;
  v.sourceHeight = desc.ModeDesc.Height;
  return true;
}

bool setupProcessor(Video& v) {
  D3D11_TEXTURE2D_DESC desktopDesc = {};
  desktopDesc.Width = v.sourceWidth;
  desktopDesc.Height = v.sourceHeight;
  desktopDesc.MipLevels = 1;
  desktopDesc.ArraySize = 1;
  desktopDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
  desktopDesc.SampleDesc.Count = 1;
  desktopDesc.Usage = D3D11_USAGE_DEFAULT;
  desktopDesc.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
  HRESULT hr = v.device->CreateTexture2D(&desktopDesc, nullptr, v.desktop.put());
  if (FAILED(hr)) return fail(v, failure("Creating the screen texture", hr));

  D3D11_VIDEO_PROCESSOR_CONTENT_DESC content = {};
  content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
  content.InputFrameRate = {static_cast<UINT>(v.fps), 1};
  content.InputWidth = v.sourceWidth;
  content.InputHeight = v.sourceHeight;
  content.OutputFrameRate = {static_cast<UINT>(v.fps), 1};
  content.OutputWidth = v.width;
  content.OutputHeight = v.height;
  content.Usage = D3D11_VIDEO_USAGE_OPTIMAL_QUALITY;
  hr = v.videoDevice->CreateVideoProcessorEnumerator(&content, v.processorEnum.put());
  if (FAILED(hr)) return fail(v, failure("Setting up the video processor", hr));
  hr = v.videoDevice->CreateVideoProcessor(v.processorEnum.get(), 0, v.processor.put());
  if (FAILED(hr)) return fail(v, failure("Creating the video processor", hr));

  D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDesc = {};
  inputDesc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
  hr = v.videoDevice->CreateVideoProcessorInputView(v.desktop.get(), v.processorEnum.get(), &inputDesc, v.inputView.put());
  if (FAILED(hr)) return fail(v, failure("Creating the video processor input", hr));

  D3D11_TEXTURE2D_DESC nv12 = {};
  nv12.Width = v.width;
  nv12.Height = v.height;
  nv12.MipLevels = 1;
  nv12.ArraySize = 1;
  nv12.Format = DXGI_FORMAT_NV12;
  nv12.SampleDesc.Count = 1;
  nv12.Usage = D3D11_USAGE_DEFAULT;
  nv12.BindFlags = D3D11_BIND_RENDER_TARGET;
  D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc = {};
  outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
  for (int i = 0; i < kRing; i++) {
    hr = v.device->CreateTexture2D(&nv12, nullptr, v.ring[i].put());
    if (FAILED(hr)) return fail(v, failure("Creating the NV12 textures", hr));
    hr = v.videoDevice->CreateVideoProcessorOutputView(v.ring[i].get(), v.processorEnum.get(), &outputDesc, v.ringViews[i].put());
    if (FAILED(hr)) return fail(v, failure("Creating the video processor output", hr));
  }

  // Full-range RGB in, BT.709 TV-range YUV out.
  D3D11_VIDEO_PROCESSOR_COLOR_SPACE inputSpace = {};
  inputSpace.RGB_Range = 0;
  v.videoContext->VideoProcessorSetStreamColorSpace(v.processor.get(), 0, &inputSpace);
  D3D11_VIDEO_PROCESSOR_COLOR_SPACE outputSpace = {};
  outputSpace.YCbCr_Matrix = 1;
  outputSpace.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
  v.videoContext->VideoProcessorSetOutputColorSpace(v.processor.get(), &outputSpace);
  RECT source = {0, 0, static_cast<LONG>(v.sourceWidth), static_cast<LONG>(v.sourceHeight)};
  RECT target = {0, 0, v.width, v.height};
  v.videoContext->VideoProcessorSetStreamFrameFormat(v.processor.get(), 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
  v.videoContext->VideoProcessorSetStreamSourceRect(v.processor.get(), 0, TRUE, &source);
  v.videoContext->VideoProcessorSetStreamDestRect(v.processor.get(), 0, TRUE, &target);
  v.videoContext->VideoProcessorSetOutputTargetRect(v.processor.get(), TRUE, &target);
  v.videoContext->VideoProcessorSetStreamAutoProcessingMode(v.processor.get(), 0, FALSE);
  return true;
}

bool setupVideo(Video& v) {
  std::vector<OutputRef> outputs = enumerateOutputs();
  if (v.screen < 0 || v.screen >= static_cast<int>(outputs.size())) return fail(v, "That screen isn't there");
  const OutputRef& ref = outputs[v.screen];
  Com<IDXGIFactory1> factory;
  HRESULT hr = CreateDXGIFactory1(__uuidof(IDXGIFactory1), factory.putVoid());
  if (FAILED(hr)) return fail(v, failure("Opening DXGI", hr));
  if (FAILED(factory->EnumAdapters1(ref.adapter, v.adapter.put()))) return fail(v, "The screen's graphics adapter is gone");
  Com<IDXGIOutput> output;
  if (FAILED(v.adapter->EnumOutputs(ref.output, output.put())) || FAILED(output.as(v.output))) {
    return fail(v, "The screen is gone");
  }

  const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
  const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
  hr = D3D11CreateDevice(v.adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags, levels, 2, D3D11_SDK_VERSION,
                         v.device.put(), nullptr, v.context.put());
  if (hr == E_INVALIDARG) {
    hr = D3D11CreateDevice(v.adapter.get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, flags, levels + 1, 1, D3D11_SDK_VERSION,
                           v.device.put(), nullptr, v.context.put());
  }
  if (FAILED(hr)) return fail(v, failure("Creating the Direct3D device", hr));
  // The encoder uses the device from its own threads.
  Com<ID3D10Multithread> multithread;
  if (SUCCEEDED(v.device.as(multithread))) multithread->SetMultithreadProtected(TRUE);
  if (FAILED(v.device.as(v.videoDevice)) || FAILED(v.context.as(v.videoContext))) {
    return fail(v, "The graphics adapter has no video processing");
  }

  if (!createDuplication(v, &hr)) {
    if (hr == DXGI_ERROR_UNSUPPORTED) return fail(v, "Windows doesn't let this screen be captured");
    if (hr != E_ACCESSDENIED) return fail(v, failure("Starting screen capture", hr));
    // The computer is locked: capture starts once it is unlocked.
    v.sourceWidth = ref.desc.DesktopCoordinates.right - ref.desc.DesktopCoordinates.left;
    v.sourceHeight = ref.desc.DesktopCoordinates.bottom - ref.desc.DesktopCoordinates.top;
  }
  if (!setupProcessor(v) || !setupEncoder(v)) return false;
  if (!v.hardware) {
    D3D11_TEXTURE2D_DESC staging = {};
    v.ring[0]->GetDesc(&staging);
    staging.Usage = D3D11_USAGE_STAGING;
    staging.BindFlags = 0;
    staging.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    hr = v.device->CreateTexture2D(&staging, nullptr, v.staging.put());
    if (FAILED(hr)) return fail(v, failure("Creating the staging texture", hr));
  }
  return true;
}

void applySettings(Video& v) {
  if (!v.codecApi) return;
  EncoderLocked l(v);
  if (v.keyframeWanted.exchange(false)) setCodecValue(v.codecApi.get(), kForceKeyFrame, 1);
  int bitrate = v.bitrate.load();
  if (bitrate != v.appliedBitrate) {
    setCodecValue(v.codecApi.get(), kMeanBitRate, bitrate);
    v.appliedBitrate = bitrate;
  }
}

// The NV12 picture in system memory, for the software encoder.
bool copyToMemory(Video& v, int slot, IMFMediaBuffer** result) {
  const DWORD size = v.width * v.height * 3 / 2;
  Com<IMFMediaBuffer> buffer;
  if (FAILED(MFCreateMemoryBuffer(size, buffer.put()))) return fail(v, "Allocating an input buffer failed");
  v.context->CopyResource(v.staging.get(), v.ring[slot].get());
  D3D11_MAPPED_SUBRESOURCE mapped;
  HRESULT hr = v.context->Map(v.staging.get(), 0, D3D11_MAP_READ, 0, &mapped);
  if (FAILED(hr)) return fail(v, failure("Reading the picture", hr));
  BYTE* target = nullptr;
  buffer->Lock(&target, nullptr, nullptr);
  const BYTE* source = static_cast<const BYTE*>(mapped.pData);
  // The chroma rows follow the luma rows, each at the mapped pitch.
  for (int row = 0; row < v.height * 3 / 2; row++) {
    std::memcpy(target + row * v.width, source + row * mapped.RowPitch, v.width);
  }
  buffer->Unlock();
  buffer->SetCurrentLength(size);
  v.context->Unmap(v.staging.get(), 0);
  *result = buffer.get();
  (*result)->AddRef();
  return true;
}

// Hands the current screen picture to the encoder. Returns false when the encoder can't take it
// yet (or failed, see v.failed).
bool submit(Video& v, int64_t ptsNs) {
  int slot;
  {
    Locked l(v.lock);
    if (v.async) {
      if (v.needInput == 0 || v.inFlight >= kMaxInFlight) return false;
      // Counted before the encoder has it, since its output can come before ProcessInput returns.
      v.needInput--;
      v.inFlight++;
    }
    slot = v.nextSlot;
    v.nextSlot = (v.nextSlot + 1) % kRing;
  }
  bool handed = false;
  struct Undo {
    Video& v;
    bool& handed;
    ~Undo() {
      if (handed || !v.async) return;
      Locked l(v.lock);
      v.needInput++;
      v.inFlight--;
    }
  } undo{v, handed};
  D3D11_VIDEO_PROCESSOR_STREAM stream = {};
  stream.Enable = TRUE;
  stream.pInputSurface = v.inputView.get();
  HRESULT hr = v.videoContext->VideoProcessorBlt(v.processor.get(), v.ringViews[slot].get(), 0, 1, &stream);
  if (FAILED(hr)) return fail(v, failure("Converting the picture", hr));

  Com<IMFSample> sample;
  Com<IMFMediaBuffer> buffer;
  if (FAILED(MFCreateSample(sample.put()))) return fail(v, "Allocating a sample failed");
  if (v.hardware) {
    hr = MFCreateDXGISurfaceBuffer(__uuidof(ID3D11Texture2D), v.ring[slot].get(), 0, FALSE, buffer.put());
    if (FAILED(hr)) return fail(v, failure("Wrapping the picture", hr));
  } else if (!copyToMemory(v, slot, buffer.put())) {
    return false;
  }
  sample->AddBuffer(buffer.get());
  sample->SetSampleTime(ptsNs / 100);
  sample->SetSampleDuration(10'000'000 / v.fps);
  applySettings(v);
  {
    EncoderLocked l(v);
    hr = v.encoder->ProcessInput(0, sample.get(), 0);
  }
  if (hr == MF_E_NOTACCEPTING) return false;
  if (FAILED(hr)) return fail(v, failure("Encoding", hr));
  handed = true;
  if (!v.async) {
    while (takeOutput(v) > 0) {
    }
  }
  return true;
}

void waitBriefly(Video& v, DWORD ms) {
  Locked l(v.lock);
  if (v.running) SleepConditionVariableCS(&v.changed, &v.lock, ms);
}

void captureLoop(Video& v) {
  const int64_t minIntervalNs = std::max<int64_t>(1'000'000, 1'000'000'000 / v.fps - 2'000'000);
  bool dirty = false;
  bool haveImage = false;
  int64_t imagePtsNs = 0;
  int64_t lastSubmitNs = 0;
  int64_t lastPtsNs = 0;
  int64_t lostSinceNs = nowNs();
  int64_t lastReopenNs = 0;
  while (v.running) {
    int64_t now = nowNs();
    if (!v.duplication && now - lastReopenNs >= kReopenEveryNs) {
      lastReopenNs = now;
      UINT oldWidth = v.sourceWidth;
      UINT oldHeight = v.sourceHeight;
      HRESULT hr;
      if (createDuplication(v, &hr)) {
        lostSinceNs = 0;
        if (v.sourceWidth != oldWidth || v.sourceHeight != oldHeight) {
          Locked l(v.lock);
          v.sourceChanged = true;
          WakeAllConditionVariable(&v.changed);
          return;
        }
      } else if (hr != E_ACCESSDENIED && now - lostSinceNs > kReopenGiveUpNs) {
        // Denied means locked, or a UAC prompt: that ends by itself. Anything else this long doesn't.
        fail(v, failure("Capturing the screen again", hr));
        return;
      }
    }

    int64_t waitNs = 50'000'000;
    if (dirty) {
      waitNs = std::max<int64_t>(0, lastSubmitNs + minIntervalNs - now);
    } else if (haveImage) {
      waitNs = std::min(waitNs, std::max<int64_t>(0, lastSubmitNs + kRepeatAfterNs - now));
    }
    if (v.duplication) {
      DXGI_OUTDUPL_FRAME_INFO info;
      Com<IDXGIResource> resource;
      HRESULT hr = v.duplication->AcquireNextFrame(static_cast<UINT>(waitNs / 1'000'000), &info, resource.put());
      if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL) {
        // A resolution change, a full-screen game or the secure desktop: capture anew.
        v.duplication.reset();
        lostSinceNs = nowNs();
        lastReopenNs = 0;
        continue;
      } else if (FAILED(hr) && hr != DXGI_ERROR_WAIT_TIMEOUT) {
        fail(v, failure("Capturing the screen", hr));
        return;
      } else if (SUCCEEDED(hr)) {
        // A frame with only a mouse move has no present time; the cursor isn't drawn. The first one
        // holds the whole screen, though, also when nothing has changed yet.
        if (info.LastPresentTime.QuadPart != 0 || !haveImage) {
          Com<ID3D11Texture2D> texture;
          D3D11_TEXTURE2D_DESC desc = {};
          if (SUCCEEDED(resource.as(texture))) texture->GetDesc(&desc);
          if (desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM) {
            v.duplication->ReleaseFrame();
            fail(v, "HDR screens can't be mirrored yet");
            return;
          }
          v.context->CopyResource(v.desktop.get(), texture.get());
          dirty = true;
          haveImage = true;
          imagePtsNs = info.LastPresentTime.QuadPart != 0 ? qpcToNs(info.LastPresentTime.QuadPart) : nowNs();
        }
        resource.reset();
        v.duplication->ReleaseFrame();
      }
    } else {
      waitBriefly(v, static_cast<DWORD>(std::min<int64_t>(waitNs, kReopenEveryNs) / 1'000'000));
    }

    now = nowNs();
    if (!dirty && haveImage && now - lastSubmitNs >= kRepeatAfterNs) {
      dirty = true;
      imagePtsNs = now;
    }
    if (dirty && now - lastSubmitNs >= minIntervalNs) {
      int64_t pts = std::max(imagePtsNs, lastPtsNs + 1'000'000);
      if (submit(v, pts)) {
        dirty = false;
        lastSubmitNs = now;
        lastPtsNs = pts;
      } else if (v.failed) {
        return;
      } else {
        waitBriefly(v, 5);
      }
    }
  }
}

void finishEncoder(Video& v) {
  if (!v.encoder) return;
  if (v.async && v.eventThread) {
    {
      EncoderLocked l(v);
      v.encoder->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
      v.encoder->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
    }
    if (WaitForSingleObject(v.eventThread, 2000) == WAIT_TIMEOUT) {
      Com<IMFShutdown> shutdown;
      if (SUCCEEDED(v.encoder.as(shutdown))) shutdown->Shutdown();
      WaitForSingleObject(v.eventThread, 2000);
    }
  }
  if (v.activate) v.activate->ShutdownObject();
}

DWORD WINAPI captureMain(void* argument) {
  Video& v = *static_cast<Video*>(argument);
  ComApartment apartment;
  MFStartup(MF_VERSION, MFSTARTUP_LITE);
  bool ok = setupVideo(v);
  if (ok && v.async) {
    v.eventThread = CreateThread(nullptr, 0, eventMain, &v, 0, nullptr);
    if (!v.eventThread) ok = fail(v, "Starting the encoder thread failed");
  }
  {
    Locked l(v.lock);
    v.ready = true;
    WakeAllConditionVariable(&v.changed);
  }
  if (ok) captureLoop(v);
  finishEncoder(v);
  if (v.eventThread && WaitForSingleObject(v.eventThread, 0) == WAIT_TIMEOUT) {
    // An encoder that never finished draining still has its thread waiting on it: keep it all.
    return 0;
  }
  v.events.reset();
  v.codecApi.reset();
  v.encoder.reset();
  v.activate.reset();
  v.deviceManager.reset();
  v.staging.reset();
  for (int i = 0; i < kRing; i++) {
    v.ringViews[i].reset();
    v.ring[i].reset();
  }
  v.inputView.reset();
  v.processor.reset();
  v.processorEnum.reset();
  v.desktop.reset();
  v.duplication.reset();
  v.videoContext.reset();
  v.videoDevice.reset();
  v.context.reset();
  v.device.reset();
  v.output.reset();
  v.adapter.reset();
  MFShutdown();
  return 0;
}

// ---------------------------------------------------------------------------------------------
// Sound

struct AudioChunk {
  int64_t ptsNs;
  std::vector<float> samples;  // interleaved stereo
};

struct Audio {
  std::atomic<bool> running{true};
  CRITICAL_SECTION lock;
  CONDITION_VARIABLE changed;
  std::deque<AudioChunk> chunks;
  size_t queuedFrames = 0;
  std::string error;
  bool failed = false;
  bool ready = false;
  HANDLE thread = nullptr;

  // The format the device delivers.
  int channels = 2;
  bool isFloat = true;
  int bitsPerSample = 32;

  // The time of the next sample, so the sound stays on one timeline.
  int64_t nextNs = -1;

  Audio() {
    InitializeCriticalSection(&lock);
    InitializeConditionVariable(&changed);
  }

  ~Audio() { DeleteCriticalSection(&lock); }
};

bool fail(Audio& a, const std::string& message) {
  Locked l(a.lock);
  if (!a.failed) {
    a.failed = true;
    a.error = message;
  }
  WakeAllConditionVariable(&a.changed);
  return false;
}

void queue(Audio& a, int64_t ptsNs, std::vector<float>&& samples) {
  if (samples.empty()) return;
  Locked l(a.lock);
  a.queuedFrames += samples.size() / kChannels;
  a.chunks.push_back({ptsNs, std::move(samples)});
  while (a.queuedFrames > kMaxQueuedAudioFrames) {
    a.queuedFrames -= a.chunks.front().samples.size() / kChannels;
    a.chunks.pop_front();
  }
  WakeAllConditionVariable(&a.changed);
}

int64_t framesToNs(int64_t frames) { return frames * 1'000'000'000 / kSampleRate; }

int64_t nsToFrames(int64_t ns) { return ns * kSampleRate / 1'000'000'000; }

void queueSilence(Audio& a, int64_t untilNs) {
  int64_t frames = nsToFrames(untilNs - a.nextNs);
  if (frames <= 0) return;
  queue(a, a.nextNs, std::vector<float>(frames * kChannels, 0.0f));
  a.nextNs += framesToNs(frames);
}

// One packet from the device, converted to stereo float and put on the timeline.
void takePacket(Audio& a, const BYTE* data, UINT32 frames, DWORD flags, int64_t ptsNs) {
  if (flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR || ptsNs <= 0) ptsNs = a.nextNs >= 0 ? a.nextNs : nowNs();
  UINT32 skip = 0;
  if (a.nextNs >= 0) {
    int64_t gap = ptsNs - a.nextNs;
    if (gap > kAudioJitterNs) {
      queueSilence(a, ptsNs);
    } else if (gap < -kAudioJitterNs) {
      // Silence was added for this time already; drop what overlaps it.
      skip = static_cast<UINT32>(std::min<int64_t>(frames, nsToFrames(-gap)));
    }
    ptsNs = a.nextNs;
  }
  if (skip >= frames) return;
  std::vector<float> samples((frames - skip) * kChannels, 0.0f);
  if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
    const int bytesPerSample = a.bitsPerSample / 8;
    for (UINT32 i = skip; i < frames; i++) {
      for (int c = 0; c < kChannels; c++) {
        const BYTE* sample = data + (static_cast<size_t>(i) * a.channels + std::min(c, a.channels - 1)) * bytesPerSample;
        float value;
        if (a.isFloat) {
          std::memcpy(&value, sample, sizeof value);
        } else if (a.bitsPerSample == 16) {
          int16_t s;
          std::memcpy(&s, sample, sizeof s);
          value = s / 32768.0f;
        } else {
          int32_t s;
          std::memcpy(&s, sample, sizeof s);
          value = static_cast<float>(s / 2147483648.0);
        }
        samples[(i - skip) * kChannels + c] = value;
      }
    }
  }
  a.nextNs = ptsNs + framesToNs(frames - skip);
  queue(a, ptsNs, std::move(samples));
}

bool startLoopback(Audio& a, IMMDevice* device, Com<IAudioClient>& client) {
  // Stereo float at 48 kHz, converted by Windows when the device mixes another way.
  WAVEFORMATEXTENSIBLE wanted = {};
  wanted.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
  wanted.Format.nChannels = kChannels;
  wanted.Format.nSamplesPerSec = kSampleRate;
  wanted.Format.wBitsPerSample = 32;
  wanted.Format.nBlockAlign = kChannels * 4;
  wanted.Format.nAvgBytesPerSec = kSampleRate * kChannels * 4;
  wanted.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
  wanted.Samples.wValidBitsPerSample = 32;
  wanted.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
  wanted.SubFormat = kFloatSubtype;
  const REFERENCE_TIME buffer = 2'000'000;  // 200 ms
  HRESULT hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, client.putVoid());
  if (FAILED(hr)) return fail(a, failure("Opening the sound device", hr));
  hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                          AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                          buffer, 0, &wanted.Format, nullptr);
  if (SUCCEEDED(hr)) return true;

  // Older Windows: take the device's own format, as long as it runs at 48 kHz.
  hr = device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, client.putVoid());
  if (FAILED(hr)) return fail(a, failure("Opening the sound device", hr));
  WAVEFORMATEX* mix = nullptr;
  hr = client->GetMixFormat(&mix);
  if (FAILED(hr)) return fail(a, failure("Reading the sound format", hr));
  bool ok = false;
  do {
    if (mix->nSamplesPerSec != kSampleRate) {
      fail(a, "The sound device runs at " + std::to_string(mix->nSamplesPerSec) + " Hz; Beamline needs 48000 Hz");
      break;
    }
    GUID subtype = kPcmSubtype;
    if (mix->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
      subtype = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(mix)->SubFormat;
    } else if (mix->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) {
      subtype = kFloatSubtype;
    }
    a.channels = mix->nChannels;
    a.bitsPerSample = mix->wBitsPerSample;
    a.isFloat = subtype == kFloatSubtype;
    if ((a.isFloat && a.bitsPerSample != 32) || (!a.isFloat && a.bitsPerSample != 16 && a.bitsPerSample != 32)) {
      fail(a, "The sound device's format isn't supported");
      break;
    }
    hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, buffer, 0, mix, nullptr);
    if (FAILED(hr)) {
      fail(a, failure("Starting sound capture", hr));
      break;
    }
    ok = true;
  } while (false);
  CoTaskMemFree(mix);
  return ok;
}

DWORD WINAPI audioMain(void* argument) {
  Audio& a = *static_cast<Audio*>(argument);
  ComApartment apartment;
  Com<IMMDeviceEnumerator> enumerator;
  Com<IMMDevice> device;
  Com<IAudioClient> client;
  Com<IAudioCaptureClient> capture;
  bool ok = false;
  do {
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator),
                                  enumerator.putVoid());
    if (FAILED(hr)) {
      fail(a, failure("Listing the sound devices", hr));
      break;
    }
    hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, device.put());
    if (FAILED(hr)) {
      fail(a, "There is no sound output to capture");
      break;
    }
    if (!startLoopback(a, device.get(), client)) break;
    hr = client->GetService(__uuidof(IAudioCaptureClient), capture.putVoid());
    if (FAILED(hr)) {
      fail(a, failure("Starting sound capture", hr));
      break;
    }
    hr = client->Start();
    if (FAILED(hr)) {
      fail(a, failure("Starting sound capture", hr));
      break;
    }
    ok = true;
  } while (false);
  {
    Locked l(a.lock);
    a.ready = true;
    WakeAllConditionVariable(&a.changed);
  }
  if (!ok) return 0;

  a.nextNs = nowNs();
  while (a.running) {
    Sleep(5);
    UINT32 packetFrames = 0;
    while (SUCCEEDED(capture->GetNextPacketSize(&packetFrames)) && packetFrames > 0) {
      BYTE* data = nullptr;
      UINT32 frames = 0;
      DWORD flags = 0;
      UINT64 position = 0;
      UINT64 qpc = 0;
      HRESULT hr = capture->GetBuffer(&data, &frames, &flags, &position, &qpc);
      if (FAILED(hr)) {
        fail(a, failure("Capturing sound", hr));
        a.running = false;
        break;
      }
      // The device's timestamp is in 100 ns units of the performance counter's clock.
      takePacket(a, data, frames, flags, static_cast<int64_t>(qpc) * 100);
      capture->ReleaseBuffer(frames);
    }
    // Windows sends nothing while nothing plays.
    int64_t now = nowNs();
    if (now - a.nextNs > 2 * kSilenceLagNs) queueSilence(a, now - kSilenceLagNs);
  }
  client->Stop();
  return 0;
}

}  // namespace

// ---------------------------------------------------------------------------------------------
// Exports

BM_API int64_t bm_now_ns() { return nowNs(); }

BM_API const char* bm_last_error() { return lastError.c_str(); }

BM_API int bm_screen_count() { return static_cast<int>(enumerateOutputs().size()); }

// The screen's place on the desktop and its size in pixels. Returns 1 for the primary screen, 0
// for another one, and -1 when there is no such screen.
BM_API int bm_screen_info(int index, int* x, int* y, int* width, int* height) {
  std::vector<OutputRef> outputs = enumerateOutputs();
  if (index < 0 || index >= static_cast<int>(outputs.size())) return -1;
  const RECT& r = outputs[index].desc.DesktopCoordinates;
  *x = r.left;
  *y = r.top;
  *width = r.right - r.left;
  *height = r.bottom - r.top;
  return isPrimary(outputs[index].desc) ? 1 : 0;
}

// Starts capturing screen [screen] (see bm_screen_info) as H.264 of the given size, frame rate and
// bitrate. Returns null on failure, see bm_last_error.
BM_API void* bm_video_open(int screen, int width, int height, int fps, int bitrate) {
  Video* v = new Video();
  v->screen = screen;
  v->width = width;
  v->height = height;
  v->fps = std::max(1, fps);
  v->bitrate = bitrate;
  v->captureThread = CreateThread(nullptr, 0, captureMain, v, 0, nullptr);
  if (!v->captureThread) {
    delete v;
    lastError = "Starting the capture thread failed";
    return nullptr;
  }
  {
    Locked l(v->lock);
    while (!v->ready) SleepConditionVariableCS(&v->changed, &v->lock, INFINITE);
  }
  if (v->failed) {
    lastError = v->error;
    v->running = false;
    WaitForSingleObject(v->captureThread, INFINITE);
    CloseHandle(v->captureThread);
    if (v->eventThread) CloseHandle(v->eventThread);
    delete v;
    return nullptr;
  }
  return v;
}

// The next encoded frame (Annex-B) into [buffer]: returns its length, 0 when none came within
// [timeoutMs], -1 when capture failed (see bm_video_error), and -2 when the screen changed size
// and capture must start again. Flags: 1 for a keyframe. A frame larger than [capacity] is dropped.
BM_API int bm_video_read(void* handle, uint8_t* buffer, int capacity, int64_t* ptsNs, int* flags, int timeoutMs) {
  Video& v = *static_cast<Video*>(handle);
  Locked l(v.lock);
  const int64_t deadline = nowNs() + static_cast<int64_t>(timeoutMs) * 1'000'000;
  while (v.packets.empty() && !v.failed && !v.sourceChanged) {
    int64_t left = deadline - nowNs();
    if (left <= 0) break;
    SleepConditionVariableCS(&v.changed, &v.lock, static_cast<DWORD>(left / 1'000'000 + 1));
  }
  if (v.failed) return -1;
  if (v.sourceChanged && v.packets.empty()) return -2;
  if (v.packets.empty()) return 0;
  Packet packet = std::move(v.packets.front());
  v.packets.pop_front();
  if (static_cast<int>(packet.data.size()) > capacity) return 0;
  std::memcpy(buffer, packet.data.data(), packet.data.size());
  *ptsNs = packet.ptsNs;
  *flags = packet.flags;
  return static_cast<int>(packet.data.size());
}

BM_API const char* bm_video_error(void* handle) {
  Video& v = *static_cast<Video*>(handle);
  Locked l(v.lock);
  lastError = v.error;
  return lastError.c_str();
}

BM_API const char* bm_video_encoder(void* handle) { return static_cast<Video*>(handle)->encoderName.c_str(); }

BM_API int bm_video_hardware(void* handle) { return static_cast<Video*>(handle)->hardware ? 1 : 0; }

BM_API void bm_video_request_keyframe(void* handle) { static_cast<Video*>(handle)->keyframeWanted = true; }

BM_API void bm_video_set_bitrate(void* handle, int bitrate) { static_cast<Video*>(handle)->bitrate = bitrate; }

BM_API void bm_video_close(void* handle) {
  Video* v = static_cast<Video*>(handle);
  v->running = false;
  {
    Locked l(v->lock);
    WakeAllConditionVariable(&v->changed);
  }
  WaitForSingleObject(v->captureThread, INFINITE);
  CloseHandle(v->captureThread);
  if (v->eventThread) {
    // An encoder that never finished draining keeps its thread; the Video must outlive it.
    if (WaitForSingleObject(v->eventThread, 0) == WAIT_TIMEOUT) return;
    CloseHandle(v->eventThread);
  }
  delete v;
}

// Starts capturing what the default sound output plays. Returns null on failure, see bm_last_error.
BM_API void* bm_audio_open() {
  Audio* a = new Audio();
  a->thread = CreateThread(nullptr, 0, audioMain, a, 0, nullptr);
  if (!a->thread) {
    delete a;
    lastError = "Starting the sound thread failed";
    return nullptr;
  }
  {
    Locked l(a->lock);
    while (!a->ready) SleepConditionVariableCS(&a->changed, &a->lock, INFINITE);
  }
  if (a->failed) {
    lastError = a->error;
    a->running = false;
    WaitForSingleObject(a->thread, INFINITE);
    CloseHandle(a->thread);
    delete a;
    return nullptr;
  }
  return a;
}

// Up to [capacity] stereo frames (interleaved floats) into [buffer]: returns how many, 0 when none
// came within [timeoutMs], and -1 when capture failed (see bm_audio_error).
BM_API int bm_audio_read(void* handle, float* buffer, int capacity, int64_t* ptsNs, int timeoutMs) {
  Audio& a = *static_cast<Audio*>(handle);
  Locked l(a.lock);
  const int64_t deadline = nowNs() + static_cast<int64_t>(timeoutMs) * 1'000'000;
  while (a.chunks.empty() && !a.failed) {
    int64_t left = deadline - nowNs();
    if (left <= 0) break;
    SleepConditionVariableCS(&a.changed, &a.lock, static_cast<DWORD>(left / 1'000'000 + 1));
  }
  if (a.failed) return -1;
  if (a.chunks.empty()) return 0;
  AudioChunk& chunk = a.chunks.front();
  int frames = std::min(capacity, static_cast<int>(chunk.samples.size() / kChannels));
  std::memcpy(buffer, chunk.samples.data(), static_cast<size_t>(frames) * kChannels * sizeof(float));
  *ptsNs = chunk.ptsNs;
  a.queuedFrames -= frames;
  if (frames * kChannels == static_cast<int>(chunk.samples.size())) {
    a.chunks.pop_front();
  } else {
    chunk.samples.erase(chunk.samples.begin(), chunk.samples.begin() + frames * kChannels);
    chunk.ptsNs += framesToNs(frames);
  }
  return frames;
}

BM_API const char* bm_audio_error(void* handle) {
  Audio& a = *static_cast<Audio*>(handle);
  Locked l(a.lock);
  lastError = a.error;
  return lastError.c_str();
}

// Puts the connected Wi-Fi adapters in media streaming mode, which keeps them out of power saving
// while sending, as the TV's Wi-Fi lock does there. Windows undoes it when the handle is closed by
// bm_wifi_streaming_stop or when the process ends. Returns null without Wi-Fi.
BM_API void* bm_wifi_streaming_start() {
  DWORD version = 0;
  HANDLE client = nullptr;
  if (WlanOpenHandle(2, nullptr, &version, &client) != ERROR_SUCCESS) return nullptr;
  WLAN_INTERFACE_INFO_LIST* list = nullptr;
  int set = 0;
  if (WlanEnumInterfaces(client, nullptr, &list) == ERROR_SUCCESS) {
    for (DWORD i = 0; i < list->dwNumberOfItems; i++) {
      if (list->InterfaceInfo[i].isState != wlan_interface_state_connected) continue;
      BOOL on = TRUE;
      if (WlanSetInterface(client, &list->InterfaceInfo[i].InterfaceGuid, wlan_intf_opcode_media_streaming_mode, sizeof on, &on,
                           nullptr) == ERROR_SUCCESS) {
        set++;
      }
    }
    WlanFreeMemory(list);
  }
  if (set == 0) {
    WlanCloseHandle(client, nullptr);
    return nullptr;
  }
  return client;
}

BM_API void bm_wifi_streaming_stop(void* handle) { WlanCloseHandle(handle, nullptr); }

BM_API void bm_audio_close(void* handle) {
  Audio* a = static_cast<Audio*>(handle);
  a->running = false;
  WaitForSingleObject(a->thread, INFINITE);
  CloseHandle(a->thread);
  delete a;
}
