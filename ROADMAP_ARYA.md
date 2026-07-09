# Arya + Embedded Hermes — Roadmap

## Shipped
- Embedded Hermes core (no Termux)
- Memory + skills + sessions
- Sensitive action confirmation
- Backup export/import (ZIP)
- Session DB migrations (v2)
- Process-death recovery

## Next (recommended order)

### P0 — Reliability on real devices
1. OEM battery / autostart guides (Huawei EMUI, Xiaomi, Samsung)
2. Accessibility reconnect hardening after reboot
3. Signed release pipeline (stable upgrade path)
4. Hermes backup to user-chosen folder (SAF create document)

### P1 — Smarter agent
5. Stronger post-turn learning (optional LLM summary → MEMORY.md)
6. Skill auto-create after successful multi-step tasks
7. Better Persian playbooks (WhatsApp, Telegram, Divar, banking UI caution)
8. Local model quality pack (prompt + tool schemas tuned for small models)

### P2 — Product surface
9. Onboarding wizard (permissions + model + safety defaults)
10. In-chat “needs your approval” card (not only full-screen dialog)
11. Session browser (read past Hermes sessions)
12. Export chat transcript alongside Hermes backup

### P3 — Advanced Hermes parity
13. Cron / scheduled tasks inside app
14. MCP client (external tool servers)
15. Subagent delegation for parallel subtasks
16. Optional OpenAI-compatible local API for desktop clients

## Model guidance (see chat for current recommendation)
