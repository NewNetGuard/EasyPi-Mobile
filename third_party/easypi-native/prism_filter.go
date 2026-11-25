// Copyright (c) 2026 EasyPi contributors
// SPDX-License-Identifier: BSD-3-Clause

package easypinative

import (
	"encoding/binary"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var prismEnabled atomic.Bool
var prismDNSProxyEnabled atomic.Bool

// SetPrismEnabled changes filtering without creating another Android VPN.
// The transport remains active while PRISM's independent switch changes.
func SetPrismEnabled(enabled bool) {
	prismEnabled.Store(enabled)
	if !enabled {
		ClearPrismFlowRules()
		clearPrismBehaviorState()
	}
}

// SetPrismDNSProxyEnabled forces Android's opportunistic encrypted DNS to
// fall back to the VPN-provided resolver, which PRISM handles through DoH.
func SetPrismDNSProxyEnabled(enabled bool) { prismDNSProxyEnabled.Store(enabled) }

func prismShouldDropPrivateDNS(packet []byte) bool {
	if prismShouldInspect() && prismUninspectablePacket(packet) {
		return true
	}
	flow, ok := parsePrismFlow(packet)
	return prismShouldDropPrivateDNSFlow(flow, ok)
}

func prismShouldDropPrivateDNSFlow(flow prismFlowKey, ok bool) bool {
	if !prismDNSProxyEnabled.Load() {
		return false
	}
	return ok && ((flow.destPort == 853 && (flow.protocol == 6 || flow.protocol == 17)) ||
		(flow.destPort == 53 && flow.protocol == 6) ||
		(flow.destPort == 443 && flow.protocol == 17))
}

func prismUninspectablePacket(packet []byte) bool {
	if len(packet) < 1 {
		return true
	}
	if packet[0]>>4 == 4 {
		return len(packet) < 20 || binary.BigEndian.Uint16(packet[6:8])&0x3fff != 0
	}
	if packet[0]>>4 == 6 {
		if len(packet) < 40 {
			return true
		}
		switch packet[6] {
		case 0, 43, 44, 50, 51, 60:
			return true
		}
	}
	return false
}

func prismShouldInspect() bool { return prismEnabled.Load() }

type prismFlowKey struct {
	source      netip.Addr
	sourcePort  uint16
	destination netip.Addr
	destPort    uint16
	protocol    uint8
}

var prismRules = struct {
	sync.Mutex
	values map[prismFlowKey]time.Time
}{values: make(map[prismFlowKey]time.Time)}
var prismRuleCount atomic.Int32

// SetPrismFlowRule installs one short-lived exact-flow rule in the Android TUN.
// Malformed or expired rules fail open and no rule survives process restart.
func SetPrismFlowRule(clientIP string, clientPort int, serverIP string, serverPort int, protocol string, ttlMillis int64) error {
	if clientPort < 1 || clientPort > 65535 || serverPort < 1 || serverPort > 65535 {
		return fmt.Errorf("invalid flow port")
	}
	if ttlMillis < 100 || ttlMillis > 30_000 {
		return fmt.Errorf("invalid flow TTL")
	}
	source, err := netip.ParseAddr(clientIP)
	if err != nil || source.IsUnspecified() || source.IsMulticast() {
		return fmt.Errorf("invalid client address")
	}
	destination, err := netip.ParseAddr(serverIP)
	if err != nil || destination.IsUnspecified() || destination.IsMulticast() {
		return fmt.Errorf("invalid server address")
	}
	var ipProtocol uint8
	switch strings.ToLower(protocol) {
	case "tcp":
		ipProtocol = 6
	case "udp", "quic":
		ipProtocol = 17
	default:
		return fmt.Errorf("unsupported flow protocol")
	}
	key := prismFlowKey{source.Unmap(), uint16(clientPort), destination.Unmap(), uint16(serverPort), ipProtocol}
	now := time.Now()
	prismRules.Lock()
	defer prismRules.Unlock()
	prunePrismRulesLocked(now)
	if len(prismRules.values) >= 256 {
		return fmt.Errorf("too many active PRISM rules")
	}
	_, existed := prismRules.values[key]
	prismRules.values[key] = now.Add(time.Duration(ttlMillis) * time.Millisecond)
	if !existed {
		prismRuleCount.Add(1)
	}
	return nil
}

func ClearPrismFlowRules() {
	prismRules.Lock()
	clear(prismRules.values)
	prismRuleCount.Store(0)
	prismRules.Unlock()
}

func prismShouldDrop(packet []byte, now time.Time) bool {
	if prismRuleCount.Load() == 0 {
		return false
	}
	key, ok := parsePrismFlow(packet)
	return prismShouldDropFlow(key, ok, now)
}

func prismShouldDropFlow(key prismFlowKey, ok bool, now time.Time) bool {
	if !ok || prismRuleCount.Load() == 0 {
		return false
	}
	prismRules.Lock()
	defer prismRules.Unlock()
	expiresAt, found := prismRules.values[key]
	if !found {
		return false
	}
	if !expiresAt.After(now) {
		delete(prismRules.values, key)
		prismRuleCount.Add(-1)
		return false
	}
	return true
}

func prunePrismRulesLocked(now time.Time) {
	for key, expiresAt := range prismRules.values {
		if !expiresAt.After(now) {
			delete(prismRules.values, key)
			prismRuleCount.Add(-1)
		}
	}
}

func parsePrismFlow(packet []byte) (prismFlowKey, bool) {
	if len(packet) < 1 {
		return prismFlowKey{}, false
	}
	var source, destination netip.Addr
	var protocol uint8
	var transportOffset int
	switch packet[0] >> 4 {
	case 4:
		if len(packet) < 20 {
			return prismFlowKey{}, false
		}
		headerLength := int(packet[0]&0x0f) * 4
		if headerLength < 20 || len(packet) < headerLength+4 || binary.BigEndian.Uint16(packet[6:8])&0x1fff != 0 {
			return prismFlowKey{}, false
		}
		source = netip.AddrFrom4([4]byte(packet[12:16]))
		destination = netip.AddrFrom4([4]byte(packet[16:20]))
		protocol = packet[9]
		transportOffset = headerLength
	case 6:
		if len(packet) < 44 {
			return prismFlowKey{}, false
		}
		source = netip.AddrFrom16([16]byte(packet[8:24]))
		destination = netip.AddrFrom16([16]byte(packet[24:40]))
		protocol = packet[6]
		if protocol != 6 && protocol != 17 {
			return prismFlowKey{}, false
		}
		transportOffset = 40
	default:
		return prismFlowKey{}, false
	}
	if protocol != 6 && protocol != 17 || len(packet) < transportOffset+4 {
		return prismFlowKey{}, false
	}
	return prismFlowKey{
		source: source.Unmap(), sourcePort: binary.BigEndian.Uint16(packet[transportOffset : transportOffset+2]),
		destination: destination.Unmap(), destPort: binary.BigEndian.Uint16(packet[transportOffset+2 : transportOffset+4]), protocol: protocol,
	}, true
}
