# Security build and operation

Release APKs are produced by `Build-SecureRelease.ps1`. Release Gradle builds
are unsigned; the release workflow requires an external production keystore.
It never falls back to Android's debug certificate. Keep the keystore, DPAPI
password configuration, and certificate lineage outside every source/export
directory and back them up securely. Standard releases do not reuse or include
the obsolete Android debug signer. As a result, a debug-signed installation
must be removed before installing the first production release. Future recovery
builds must use the production key and an acceptable version code. Release APKs
carry both APK Signature Scheme v2 and v3 signatures for Android and
vendor-scanner compatibility.

The native build pins Go 1.27.4. `-trimpath` removes
source paths from stack metadata. gomobile also records the absolute
directories of local modules, so `Build-Libtailscale.ps1` builds through a
temporary drive mapping and the library records only `R:\easypi-native`
(or the next free drive letter). `Build-SecureRelease.ps1` refuses to sign an
APK that contains the project directory or the builder's user profile, which
also catches a stale native library used with `-SkipNative`. No private
configuration should be copied into the build tree.

Chameleon enrollment requires the matching server security update. Session
credentials are unique to each device, separate from the cover/routing key.
Deleting a device revokes Mobile API access immediately; successful completion
also publishes the VPS revocation. If VPS synchronization fails, the revoked
row is retained and deletion returns an error so it can be retried. Existing
VPN streams are canceled on registry reload; the watcher runs every 200 ms.
An old installation-wide session key is not a supported fallback.

Normal Protection, and Protection running together with the VPN, filter on the
phone. Protection waits for an authenticated domain verdict from the home
EasyPi; server errors and unknown decisions block access. On-phone filtering
blocks TCP DNS, DoT, QUIC, ECH handshakes and unsupported IP
fragmentation/extension paths to prevent silent domain-policy bypasses. DNS
HTTPS records are withheld while it is on so clients can use ordinary TLS with
A/AAAA records. Strict Protection does not filter on the phone: all traffic is
carried through the home tunnel and the home EasyPi enforces its policy. DNS is
always resolved through the authenticated Chameleon DNS endpoint when Chameleon
is selected, including with Protection off. Turning Protection off restores
these protocols; VPN payload encryption remains enabled independently.

Android's always-on/lockdown setting is a separate operating-system safeguard
against VPN process termination. The application cannot promise persistence
after its process is killed without that setting. Enabling lockdown requires
testing the control-channel/UID routing on the target Android version.

External shares require temporary content grants and explicit confirmation.
File/queue limits apply before upload; queues belong to one pairing and never
move automatically to a new pairing. Old unscoped files are retained locally
and are not automatically uploaded.

Dashboard statistics count DNS requests observed by the EasyPi DNS proxy, not
TLS policy lookups, bytes, or all traffic on the phone. Each DNS outcome supplies
both the total and its blocked flag; TLS notifications do not affect this ratio.
Counts belong to the request's local hour. Unobserved encrypted DNS is not included.
Version 0.1.2 discards the older mixed counters rather than presenting them as DNS
statistics. The chart shows hourly counts, shades future hours, and places NOW at
the actual observation time. The percentage uses one decimal place in English.
