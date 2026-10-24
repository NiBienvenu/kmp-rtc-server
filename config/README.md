# config/ (monte en lecture seule sur /etc/kmp-rtc)

- `certs/fullchain.pem` + `certs/privkey.pem` : certificat TLS (sinon auto-signe)
- `asterisk/pjsip_custom.conf` : ajoute a la fin de pjsip.conf (trunks, comptes de test)
- `asterisk/<fichier>.conf` : remplace le fichier Asterisk genere

Ne committez jamais les certificats (voir .gitignore).
