# EasyPi Protection Engine

## Scope

EasyPi Protection Engine is the data path of normal Protection: the phone
filters its own traffic with the home EasyPi's policy and sends it straight to
the internet. Strict Protection uses the home tunnel (PrismLink) instead.
It is not a general proxy and does not implement SOCKS,
HTTP CONNECT, routing scripts, or server transports. Chameleon remains an
independent transport and is not linked to this engine.

The implementation is based on RFC 791 (IPv4), RFC 8200 (IPv6), RFC 768
(UDP), RFC 9293 (TCP) and RFC 1071 (Internet checksum). No third-party
tun2socks source is copied into the engine.
The code is kept in `third_party/easypi-native` because that directory is the
source of the existing EasyPi-owned Android native binding.

## Data path

1. Android `VpnService` owns one TUN descriptor.
2. The packet pump validates a packet and runs the existing EasyPi PRISM gate.
3. Allowed UDP flows use direct connected datagram sockets.
4. Allowed TCP flows terminate the phone-side TCP session in the engine and
   use direct kernel TCP sockets for the physical network side.
5. DNS answers and remote traffic are written back to the TUN descriptor.

The EasyPi application is excluded from its own VPN, so transport sockets
leave through Android's physical network without a routing loop.

## Performance rules

- one packet parse before PRISM and transport dispatch;
- no per-packet JNI callback;
- bounded per-flow queues and memory;
- one goroutine per active remote socket, backed by Go's native network poller;
- direct sockets with no local proxy protocol or extra loopback hop;
- PRISM policy callbacks only for new DNS or TLS metadata.

## Security rules

- malformed or fragmented protected traffic fails closed in PRISM;
- a flow is never opened before PRISM permits its first packet;
- unknown TLS domains remain held until the authenticated policy responds;
- transport state and buffers are bounded;
- stopping Protection closes the TUN and all direct sockets;
- Chameleon code, keys and protocol are outside this engine.
