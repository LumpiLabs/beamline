/*
 * A dnssd backend for UxPlay that announces nothing itself: Fire OS already runs an mDNS responder,
 * and the TV app announces through NsdManager, as it does _beamline._tcp. The TXT records are the
 * ones UxPlay's dns_sd and mdnsd backends build (lib/dns_sd/dns_sd.c), in DNS wire format, because
 * UxPlay also sends them back to clients in its /info answers.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd_android.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define TXT_MAX 1024
#define NAME_MAX_LEN 256

typedef struct {
    unsigned char bytes[TXT_MAX];
    int length;
} txt_record_t;

typedef struct {
    txt_record_t txt;
    char name[NAME_MAX_LEN];
    unsigned short port;
    int registered;
} service_t;

typedef struct {
    service_t raop;
    service_t airplay;
} dnssd_private_t;

static int txt_add(txt_record_t *txt, const char *key, const char *value)
{
    size_t entry = strlen(key) + 1 + strlen(value);
    /* the length byte, the entry, and room for snprintf's terminating zero */
    if (entry > 255 || txt->length + 1 + (int) entry + 1 > TXT_MAX) {
        return -1;
    }
    txt->bytes[txt->length++] = (unsigned char) entry;
    txt->length += snprintf((char *) txt->bytes + txt->length, entry + 1, "%s=%s", key, value);
    return 0;
}

static dnssd_private_t *private_of(dnssd_t *dnssd)
{
    return (dnssd_private_t *) dnssd->dnssd_private;
}

static service_t *service_of(dnssd_t *dnssd, int service)
{
    return service == DNSSD_SERVICE_RAOP ? &private_of(dnssd)->raop : &private_of(dnssd)->airplay;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error)
{
    (void) dnssd_public;
    dnssd_private_t *dnssd = calloc(1, sizeof(dnssd_private_t));
    if (!dnssd && error) {
        *error = DNSSD_ERROR_OUTOFMEM;
    }
    return dnssd;
}

void dnssd_private_destroy(void *dnssd_private)
{
    free(dnssd_private);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port)
{
    service_t *service = service_of(dnssd_public, DNSSD_SERVICE_RAOP);
    char features[22];
    char hwaddr[2 * MAX_HWADDR_LEN + 1];
    const char *pw = "false";
    const char *sf = RAOP_SF;

    switch (dnssd_public->pin_pw) {
    case 1: /* status flag 0x08: PIN required */
        pw = "true";
        sf = "0x8c";
        break;
    case 2: /* status flag 0x80: password required */
    case 3:
        pw = "true";
        sf = "0x84";
        break;
    default:
        break;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt_record_t *txt = &service->txt;
    txt->length = 0;
    if (txt_add(txt, "ch", RAOP_CH) || txt_add(txt, "cn", RAOP_CN) || txt_add(txt, "da", RAOP_DA) ||
        txt_add(txt, "et", RAOP_ET) || txt_add(txt, "vv", RAOP_VV) || txt_add(txt, "ft", features) ||
        txt_add(txt, "am", GLOBAL_MODEL) || txt_add(txt, "md", RAOP_MD) || txt_add(txt, "rhd", RAOP_RHD) ||
        txt_add(txt, "pw", pw) || txt_add(txt, "sf", sf) || txt_add(txt, "sr", RAOP_SR) ||
        txt_add(txt, "ss", RAOP_SS) || txt_add(txt, "sv", RAOP_SV) || txt_add(txt, "tp", RAOP_TP) ||
        txt_add(txt, "txtvers", RAOP_TXTVERS) || txt_add(txt, "vs", RAOP_VS) || txt_add(txt, "vn", RAOP_VN) ||
        txt_add(txt, "pk", dnssd_public->pk ? dnssd_public->pk : "")) {
        return -1;
    }

    if (utils_hwaddr_raop(hwaddr, sizeof(hwaddr), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    if (snprintf(service->name, sizeof(service->name), "%s@%s", hwaddr, dnssd_public->name) >= NAME_MAX_LEN) {
        return -2;
    }
    service->port = port;
    service->registered = 1;
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port)
{
    service_t *service = service_of(dnssd_public, DNSSD_SERVICE_AIRPLAY);
    char features[22];
    char device_id[3 * MAX_HWADDR_LEN];

    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) {
        return -1;
    }
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt_record_t *txt = &service->txt;
    txt->length = 0;
    if (txt_add(txt, "deviceid", device_id) || txt_add(txt, "features", features) ||
        txt_add(txt, "pw", dnssd_public->pin_pw ? "true" : "false") || txt_add(txt, "flags", "0x4") ||
        txt_add(txt, "model", GLOBAL_MODEL) || txt_add(txt, "pk", dnssd_public->pk ? dnssd_public->pk : "") ||
        txt_add(txt, "pi", AIRPLAY_PI) || txt_add(txt, "srcvers", AIRPLAY_SRCVERS) ||
        txt_add(txt, "vv", AIRPLAY_VV)) {
        return -1;
    }

    if (snprintf(service->name, sizeof(service->name), "%s", dnssd_public->name) >= NAME_MAX_LEN) {
        return -2;
    }
    service->port = port;
    service->registered = 1;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public)
{
    service_of(dnssd_public, DNSSD_SERVICE_RAOP)->registered = 0;
}

void dnssd_unregister_airplay(dnssd_t *dnssd_public)
{
    service_of(dnssd_public, DNSSD_SERVICE_AIRPLAY)->registered = 0;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length)
{
    service_t *service = service_of(dnssd_public, DNSSD_SERVICE_RAOP);
    *length = service->txt.length;
    return (const char *) service->txt.bytes;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length)
{
    service_t *service = service_of(dnssd_public, DNSSD_SERVICE_AIRPLAY);
    *length = service->txt.length;
    return (const char *) service->txt.bytes;
}

int dnssd_get_service_fd(dnssd_t *dnssd_public, int service)
{
    (void) dnssd_public;
    (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd_public, int service)
{
    (void) dnssd_public;
    (void) service;
    return 0;
}

void dnssd_error_text(int *error, const char *appname)
{
    (void) error;
    (void) appname;
}

const char *dnssd_android_service_name(dnssd_t *dnssd, int service)
{
    service_t *s = service_of(dnssd, service);
    return s->registered ? s->name : NULL;
}

unsigned short dnssd_android_service_port(dnssd_t *dnssd, int service)
{
    return service_of(dnssd, service)->port;
}
