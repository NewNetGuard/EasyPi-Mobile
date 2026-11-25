# EasyPi Mobile requires no root

EasyPi Mobile runs on unmodified, non-rooted Android phones. The application
never requests root access, and none of its features depend on it.

What the application uses:

- Android's standard `VpnService`, enabled through Android's normal VPN consent
  dialog. Protection and the Chameleon VPN share this one service.
- Ordinary runtime permissions for notifications and for the photos and videos
  you choose to back up.
- Authenticated HTTPS (Mobile Link) to the home EasyPi it was paired with.

What the application does not do:

- It does not run commands as root or modify the system partition.
- It does not use an Accessibility service and does not read screen content.
- It does not extract TLS keys from browsers or other applications and does not
  decrypt their traffic. Protection decides on DNS names and TLS server names
  only.

Android's always-on VPN and "Block connections without VPN" settings are
ordinary operating-system options. They are not required, and they do not need
root either.
