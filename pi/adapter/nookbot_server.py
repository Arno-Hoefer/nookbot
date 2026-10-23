#!/usr/bin/env python3
"""
NookBot-Adapter – Startpunkt (systemd: python3 /opt/nookbot/nookbot_server.py).

Der Code liegt im Paket nookbot/ daneben (Überblick in nookbot/__init__.py). Wird diese Datei importiert (Tests, Werkzeuge),
stehen hier wie früher alle Namen des Adapters bereit: nookbot_server.bot(…), nookbot_server.SESS …
Zuweisungen – z. B. in Tests nookbot_server.unit = … – landen in dem Modul, dem der Name gehört, und wirken damit überall.
"""
import os
import sys
import types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nookbot  # noqa: E402


class _Adapter(types.ModuleType):
    def __getattr__(self, name):
        modul = nookbot.HERKUNFT.get(name)
        if modul is None:
            raise AttributeError(name)
        return getattr(getattr(nookbot, modul), name)

    def __setattr__(self, name, wert):
        modul = nookbot.HERKUNFT.get(name)
        if modul is None:
            super().__setattr__(name, wert)
        else:
            setattr(getattr(nookbot, modul), name, wert)


if __name__ == "__main__":
    nookbot.start.main()
else:
    sys.modules[__name__].__class__ = _Adapter
