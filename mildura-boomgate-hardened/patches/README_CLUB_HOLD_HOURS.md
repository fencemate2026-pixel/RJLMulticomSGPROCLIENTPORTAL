# Club hold hours: 09:55 → 01:40

**Intended behaviour (confirmed):**

| Window | Behaviour |
|--------|-----------|
| 09:55 → 01:40 next day | ORO constant hold — boom up |
| Outside that window | Keypad / PIN — 3s AP pulse, free-close |

Do **not** run `disable_constant_hold_schedule.py` for this site — that turns hold off entirely.

## Apply on Pi

```bash
mkdir -p ~/boomgate/patches
curl -fsSL -o ~/boomgate/patches/set_club_hold_hours.py \
  'https://raw.githubusercontent.com/fencemate2026-pixel/Milduraworkingmansclub/cursor/club-hold-0140-8f0c/mildura-boomgate/patches/set_club_hold_hours.py'

python3 ~/boomgate/patches/set_club_hold_hours.py --dry-run
python3 ~/boomgate/patches/set_club_hold_hours.py
sudo systemctl restart boomgate
```

Verify in club hours: `pinctrl get 23` → `hi`. Outside: `lo`.
