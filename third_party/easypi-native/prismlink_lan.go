package easypinative

import "net"

// onSameLAN reports whether the phone's current source address shares the
// home EasyPi's /24 private network. The phone cannot read the LAN prefix
// length on Android, and home networks are /24; a wider home LAN only falls
// back to the relay.
func onSameLAN(local, remote net.Addr) bool {
	localUDP, ok := local.(*net.UDPAddr)
	if !ok {
		return false
	}
	remoteUDP, ok := remote.(*net.UDPAddr)
	if !ok {
		return false
	}
	localIP, remoteIP := localUDP.IP.To4(), remoteUDP.IP.To4()
	if localIP == nil || remoteIP == nil || !remoteIP.IsPrivate() || localIP.Equal(remoteIP) {
		return false
	}
	mask := net.CIDRMask(24, 32)
	return localIP.Mask(mask).Equal(remoteIP.Mask(mask))
}
