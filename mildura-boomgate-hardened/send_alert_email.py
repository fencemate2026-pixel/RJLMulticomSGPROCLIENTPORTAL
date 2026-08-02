"""
Sends an alert email via Gmail SMTP. Reads app password from /etc/rjl/rjl.env.
Usage: python3 send_alert_email.py "Subject" "Body text"
"""
import sys
import smtplib
from email.mime.text import MIMEText

def load_env(path="/etc/rjl/rjl.env"):
    env = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                env[k] = v
    return env

def send_alert(subject, body, to_addr="info@rjlcommercialgroup.com"):
    env = load_env()
    gmail_user = env.get("GMAIL_SENDER_ADDRESS")
    gmail_pw = env.get("GMAIL_APP_PASSWORD")
    if not gmail_user or not gmail_pw:
        print("ERROR: missing GMAIL_SENDER_ADDRESS or GMAIL_APP_PASSWORD in /etc/rjl/rjl.env")
        sys.exit(1)

    msg = MIMEText(body)
    msg["Subject"] = subject
    msg["From"] = gmail_user
    msg["To"] = to_addr

    with smtplib.SMTP_SSL("smtp.gmail.com", 465) as server:
        server.login(gmail_user, gmail_pw)
        server.sendmail(gmail_user, [to_addr], msg.as_string())
    print(f"Sent to {to_addr}")

if __name__ == "__main__":
    subject = sys.argv[1] if len(sys.argv) > 1 else "Test Alert"
    body = sys.argv[2] if len(sys.argv) > 2 else "This is a test."
    send_alert(subject, body)
