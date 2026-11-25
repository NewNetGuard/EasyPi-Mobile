package easypinative

import "time"

// prismFilterOutboundVia parses the IP/TCP or IP/UDP tuple once and feeds the
// same validated view to every PRISM stage. The previous pipeline reparsed
// every packet up to four times. Keeping one immutable view reduces mobile CPU
// cost without changing fail-closed ordering or policy decisions.
func prismFilterOutboundVia(
	packet []byte,
	inject func([]byte) error,
	now time.Time,
	resolve func([]byte) ([]byte, error),
	releaseCheckedTLS func([]byte),
) bool {
	protected := prismShouldInspect()
	if protected && prismUninspectablePacket(packet) {
		if response := prismUDPUnreachable(packet); response != nil {
			_ = inject(response)
		}
		return true
	}

	flow, flowOK := parsePrismFlow(packet)
	var payload []byte
	transportOK := false
	if flowOK {
		payload, transportOK = prismTransportPayload(packet, flow)
	}
	if protected && prismShouldDropPrivateDNSFlow(flow, flowOK) {
		if response := prismUDPUnreachable(packet); response != nil {
			_ = inject(response)
		}
		return true
	}
	if prismInterceptDNSViaParsed(packet, flow, payload, flowOK && transportOK, inject, now, resolve, protected) {
		return true
	}
	if !protected {
		return false
	}
	return prismShouldDropFlow(flow, flowOK, now) ||
		prismBehaviorShouldDropParsed(packet, flow, payload, flowOK && transportOK, now, releaseCheckedTLS)
}
