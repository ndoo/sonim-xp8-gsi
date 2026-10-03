#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
"""Write a byte-reproducible otacerts.zip holding the given certificates.

usage: otacerts.py OUT.zip CERT.x509.pem...

update_engine checks payload signatures against every entry whose name ends
in "x509.pem".
"""
import os
import sys
import zipfile

out, certs = sys.argv[1], sys.argv[2:]
if not certs:
    sys.exit(__doc__)
with zipfile.ZipFile(out, "w") as z:
    for c in certs:
        name = os.path.basename(c)
        if not name.endswith("x509.pem"):
            sys.exit(f"{c}: name must end in x509.pem")
        zi = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        zi.create_system = 3
        with open(c, "rb") as f:
            z.writestr(zi, f.read(), compresslevel=9)
