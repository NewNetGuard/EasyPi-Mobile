//go:build android

package easypinative

import (
	"fmt"
	"os"
	"sync"
	"time"

	"chameleon/clientcore"
	"golang.org/x/sys/unix"
)

type easyPiChameleonSession struct {
	client *clientcore.MobileClient
	file   *os.File
	stop   chan struct{}
	wg     *sync.WaitGroup
	write  sync.Mutex
}

var easyPiChameleon = struct {
	sync.Mutex
	session *easyPiChameleonSession
}{}

// StartEasyPiChameleon attaches the existing Android-owned TUN to Chameleon.
// PRISM sees outbound packets before encryption and inbound packets after
// authenticated decryption. Android still owns the sole VpnService.
func StartEasyPiChameleon(tunFD int, configJSON, profilePath string) error {
	if tunFD < 0 {
		return fmt.Errorf("invalid TUN file descriptor")
	}
	// The detached descriptor is transferred to this binding. Keep one private
	// duplicate for the pump and close the transferred descriptor on every path.
	defer unix.Close(tunFD)
	StopEasyPiChameleon()

	duplicate, err := unix.Dup(tunFD)
	if err != nil {
		return fmt.Errorf("duplicate TUN descriptor: %w", err)
	}
	file := os.NewFile(uintptr(duplicate), "easypi-chameleon-tun")
	client := clientcore.NewMobileClient()
	if profilePath != "" {
		client.SetProfilePath(profilePath)
	}
	if message := client.SetConfigJSON(configJSON); message != "" {
		_ = file.Close()
		return fmt.Errorf("apply Chameleon config: %s", message)
	}
	if message := client.StartClient(); message != "" {
		_ = file.Close()
		return fmt.Errorf("start Chameleon: %s", message)
	}

	session := &easyPiChameleonSession{
		client: client,
		file:   file,
		stop:   make(chan struct{}),
		wg:     &sync.WaitGroup{},
	}
	session.wg.Add(2)
	easyPiChameleon.Lock()
	easyPiChameleon.session = session
	go chameleonUplink(client, file, session)
	go chameleonDownlink(client, file, session)
	easyPiChameleon.Unlock()
	return nil
}

func chameleonUplink(client *clientcore.MobileClient, file *os.File, session *easyPiChameleonSession) {
	defer session.wg.Done()
	buffer := make([]byte, 65535)
	releaseCheckedTLS := func(packet []byte) {
		now := time.Now()
		// Re-enter the complete filter after the domain decision so exact-flow
		// and behavior rules still apply to the held ClientHello.
		if prismFilterOutboundVia(packet, session.inject, now, client.ResolveDNS, nil) {
			return
		}
		select {
		case <-session.stop:
			return
		default:
			_ = client.PushPacket(packet)
		}
	}
	for {
		n, err := file.Read(buffer)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		packet := append([]byte(nil), buffer[:n]...)
		if prismFilterOutboundVia(packet, session.inject, time.Now(), client.ResolveDNS, releaseCheckedTLS) {
			continue
		}
		if message := client.PushPacket(packet); message != "" {
			select {
			case <-session.stop:
				return
			default:
			}
		}
	}
}

func chameleonDownlink(client *clientcore.MobileClient, file *os.File, session *easyPiChameleonSession) {
	defer session.wg.Done()
	for {
		select {
		case <-session.stop:
			return
		default:
		}
		packet := client.WaitPacket(100)
		if len(packet) == 0 {
			continue
		}
		// PRISM enforcement is intentionally egress-only: DNS questions, TLS
		// ClientHello records and exact flow rules all use the client-to-server
		// tuple before encryption. On a reply the tuple is reversed, so the old
		// checks could not match a rule and only reparsed every download packet.
		// The packet is still authenticated by Chameleon's inner AEAD and is then
		// validated by Android's IP/TCP stack before delivery to an application.
		if err := session.inject(packet); err != nil {
			return
		}
	}
}

func (session *easyPiChameleonSession) inject(packet []byte) error {
	session.write.Lock()
	defer session.write.Unlock()
	_, err := session.file.Write(packet)
	return err
}

func StopEasyPiChameleon() {
	easyPiChameleon.Lock()
	session := easyPiChameleon.session
	easyPiChameleon.session = nil
	easyPiChameleon.Unlock()
	if session == nil {
		ClearPrismFlowRules()
		clearPrismBehaviorState()
		return
	}

	close(session.stop)
	_ = session.file.Close()
	stopped := make(chan struct{})
	go func() {
		session.client.StopClient()
		session.wg.Wait()
		close(stopped)
	}()
	select {
	case <-stopped:
	case <-time.After(3 * time.Second):
		// Android must remain responsive even if the native transport is slow
		// to release a socket. The TUN and stop channel are already closed.
	}
	ClearPrismFlowRules()
	clearPrismBehaviorState()
}

func EasyPiChameleonStats() string {
	easyPiChameleon.Lock()
	defer easyPiChameleon.Unlock()
	if easyPiChameleon.session == nil {
		return `{"connected":false,"bytes_sent":0,"bytes_recv":0}`
	}
	return easyPiChameleon.session.client.GetStats()
}
