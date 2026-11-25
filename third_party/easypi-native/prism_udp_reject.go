package easypinative

import "encoding/binary"

// Reject blocked UDP explicitly so clients can abandon QUIC rather than wait
// on a black hole. Quote only IP/UDP headers, never application payload.
func prismUDPUnreachable(packet []byte) []byte {
	flow, payload, ok := parsePrismTransport(packet)
	if !ok || flow.protocol != 17 || flow.source.IsMulticast() || flow.destination.IsMulticast() || flow.source.IsUnspecified() || flow.destination.IsUnspecified() {
		return nil
	}
	offset := len(packet) - len(payload) - 8
	if packet[0]>>4 == 4 {
		if offset < 20 || binary.BigEndian.Uint16(packet[6:8])&0x3fff != 0 {
			return nil
		}
		response := make([]byte, 20+8+offset+8)
		response[0], response[8], response[9] = 0x45, 64, 1
		binary.BigEndian.PutUint16(response[2:4], uint16(len(response)))
		copy(response[12:16], packet[16:20])
		copy(response[16:20], packet[12:16])
		response[20], response[21] = 3, 3
		copy(response[28:], packet[:offset+8])
		binary.BigEndian.PutUint16(response[22:24], internetChecksum(response[20:], 0))
		binary.BigEndian.PutUint16(response[10:12], internetChecksum(response[:20], 0))
		return response
	}
	if packet[0]>>4 != 6 || offset != 40 {
		return nil
	}
	response := make([]byte, 40+8+48)
	response[0], response[6], response[7] = 0x60, 58, 64
	binary.BigEndian.PutUint16(response[4:6], uint16(len(response)-40))
	copy(response[8:24], packet[24:40])
	copy(response[24:40], packet[8:24])
	response[40], response[41] = 1, 4
	copy(response[48:], packet[:48])
	sum := checksumSum(response[8:40], 0) + uint32(len(response)-40) + 58
	binary.BigEndian.PutUint16(response[42:44], internetChecksum(response[40:], sum))
	return response
}
