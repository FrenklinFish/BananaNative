// Local wireless over IP through the portable co-op module (source pin and notices: android/host/CMakeLists.txt).
// Off unless DM_COOP is set (coop_env_node: DM_COOP = auto[:port] | join:<IPv4>[:port] | host:<port>, DM_UID, DM_COOP_INTERNET / DM_COOP_ROOM / DM_COOP_RELAY ...): unset,
// dm_coop_node() is NULL and the nn::ldn / nn::socket / nn::pctl hooks (hle_offline.c, hle_net.c, hle_misc.c) keep their offline answers for every game, with no log line.
// With a node, the nn::ldn calls are coop_ldn_* and the game's datagram sockets (descriptors COOP_FD_BASE.., made while the LDN is initialized) are coop_vnet_*; every other
// descriptor stays offline. Nothing here names a game: any title that uses nn::ldn reaches the same calls.
#pragma once
#include <stdint.h>
#include <stddef.h>
#if NX_COOP_VENDOR
#include "coop.h"
#else
// Built without the module (NX_COOP_VENDOR=0): dm_coop_node() is always NULL, so none of these is ever evaluated; they only let the hooks compile.
typedef struct coop_node coop_node;
typedef struct { int32_t fd; int16_t events, revents; } coop_pollfd;
#define COOP_FD_BASE 0x100
#define COOP_LDN_DEVICE_DISABLED 0x2CCBu
#define coop_ldn_initialize(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_finalize(...) ((void)0)
#define coop_ldn_state(...) (0)
#define coop_ldn_open_ap(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_close_ap(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_create_network(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_destroy_network(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_set_advertise(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_open_station(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_close_station(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_scan(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_connect(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_disconnect(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_network_info(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_ipv4(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_network_config(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_security_parameter(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_disconnect_reason(...) (0)
#define coop_ldn_set_accept_policy(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_ldn_reject(...) (COOP_LDN_DEVICE_DISABLED)
#define coop_vnet_is(...) (0)
#define coop_vnet_socket(...) (-50)
#define coop_vnet_bind(...) (-50)
#define coop_vnet_close(...) (-50)
#define coop_vnet_setsockopt(...) (-50)
#define coop_vnet_getsockopt(...) (-50)
#define coop_vnet_getsockname(...) (-50)
#define coop_vnet_fcntl(...) (-50)
#define coop_vnet_sendto(...) (-50)
#define coop_vnet_recvfrom(...) (-50)
#define coop_vnet_poll(...) (0)
#endif
void dm_sk_seterr(int e);                     // hle_net.c: the calling thread's BSD error code
int dm_sk_geterr(void);
#if NX_COOP_VENDOR
coop_node *dm_coop_node(void);                 // NULL when DM_COOP is unset, empty, "off" or "0"
int dm_coop_vfd(int fd);                       // whether fd is an allocated virtual socket
int dm_coop_ret(int64_t r);                    // -errno -> -1 and the calling thread's BSD error code
void dm_coop_set_event(void *ev);              // bound guest SystemEvent, cleared at Finalize
void dm_coop_trace(const char *fn, int64_t r); // only with a node, and only the first 400 calls
#define DM_COOP_FD_MAX COOP_FD_MAX
#else
// Inline fallbacks keep existing standalone HLE unit tests and non-Android hosts
// independent of hle_coop.c when the optional module is not compiled in.
static inline coop_node *dm_coop_node(void) { return NULL; }
static inline int dm_coop_vfd(int fd) { (void)fd; return 0; }
static inline int dm_coop_ret(int64_t r) { (void)r; return -1; }
static inline void dm_coop_set_event(void *ev) { (void)ev; }
static inline void dm_coop_trace(const char *fn, int64_t r) { (void)fn; (void)r; }
#define DM_COOP_FD_MAX 0
#endif
