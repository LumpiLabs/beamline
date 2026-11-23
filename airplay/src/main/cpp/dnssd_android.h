/*
 * The Android side of UxPlay's dnssd interface (dnssd.h). Android's NsdManager does the announcing,
 * so registering here only builds the TXT records and remembers the name and port; the JNI glue
 * reads them back and hands them to Kotlin.
 */
#ifndef BEAMLINE_DNSSD_ANDROID_H
#define BEAMLINE_DNSSD_ANDROID_H

#include "dnssd.h"

/* The instance name the service is announced under ("<TV name>", or "<device ID>@<TV name>" for
   _raop._tcp), or NULL while it isn't registered. service is DNSSD_SERVICE_RAOP or _AIRPLAY. */
const char *dnssd_android_service_name(dnssd_t *dnssd, int service);

/* The port the service was registered with. */
unsigned short dnssd_android_service_port(dnssd_t *dnssd, int service);

#endif
