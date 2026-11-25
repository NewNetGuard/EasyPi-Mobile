package easypinative

import (
	"net"
	"testing"
)

func TestOnSameLANUsesTheHomeSubnetOnly(t *testing.T) {
	udp := func(ip string) net.Addr { return &net.UDPAddr{IP: net.ParseIP(ip), Port: 9083} }
	cases := []struct {
		local, remote string
		want          bool
	}{
		{"192.168.1.42", "192.168.1.30", true},  // home Wi-Fi
		{"10.20.30.40", "192.168.1.30", false},  // hotspot / mobile data
		{"192.168.50.7", "192.168.1.30", false}, // another Wi-Fi
		{"203.0.113.9", "203.0.113.10", false},  // never a public direct address
		{"192.168.1.30", "192.168.1.30", false}, // not the EasyPi itself
	}
	for _, item := range cases {
		if got := onSameLAN(udp(item.local), udp(item.remote)); got != item.want {
			t.Fatalf("onSameLAN(%s, %s) = %v, want %v", item.local, item.remote, got, item.want)
		}
	}
}
