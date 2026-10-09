#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
"""Build the GSI vendor.img from the stock system_a image and the release components.

Reads /system/vendor (contents, owners, modes, xattrs) from the raw stock
system_a with debugfs, applies vendor/ from this repository, and writes a
1 GiB ext4 image with mke2fs + debugfs. Needs no root and no loop mount.
"""
import argparse
import hashlib
import os
import re
import shlex
import shutil
import stat
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2meta  # noqa: E402
from fclabel import FileContexts  # noqa: E402

VENDOR = '/system/vendor'
STAMP = 1230768000
IMAGE_BYTES = 1 << 30
MKE2FS_FEATURES = ('none,has_journal,ext_attr,resize_inode,dir_index,filetype,extent,'
                   'sparse_super,large_file,huge_file,uninit_bg,dir_nlink,extra_isize')
# Keys the stock vendor build.prop lacks once the GSI replaces /system (docs/technical.md#vendor-image).
PROP_HEADER = ('# --- Phase 3: device props moved from stock /system '
               '(default.prop, build.prop, sdm660_64.prop) ---\n')
PROP_DEFAULT = re.compile(r'^(ro\.zygote|dalvik\.vm\.isa\.|ro\.bionic\.|ro\.oem_unlock_supported)')
PROP_BUILD_SKIP = re.compile(
    r'^ro\.(build|system|product|com\.google|treble|apex|control_privapp|'
    r'vendor\.build\.fingerprint|wifi\.channels|config\.|carrier|hw_version|rpsservice)|'
    r'^(persist\.sys\.usb\.config|persist\.developer|pm\.dexopt|import|rild\.libpath|ro\.boot\.)')


def die(msg):
    sys.exit('mkvendor: ' + msg)


def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def debugfs_dump(image, pairs):
    """Copy files out of an image: [(image_path, local_path)]."""
    for _, dst in pairs:
        os.makedirs(os.path.dirname(dst), exist_ok=True)
    script = ''.join('dump %s %s\n' % (e2meta.q(s), e2meta.q(d)) for s, d in pairs)
    subprocess.run(['debugfs', '-f', '-', image], input=script.encode(),
                   capture_output=True, check=True)
    for s, d in pairs:
        if not os.path.isfile(d):
            die('cannot read %s from %s' % (s, image))


def debugfs_cat(image, path):
    p = subprocess.run(['debugfs', '-R', 'cat ' + e2meta.q(path), image],
                       capture_output=True, check=True)
    return p.stdout


def lines(data):
    return data.decode('utf-8', 'surrogateescape').splitlines()


def build_prop(stock, default_prop, sdm_prop, sys_prop, props_dir):
    moved = [l for l in lines(default_prop) if PROP_DEFAULT.match(l)]
    moved += [l for l in lines(sdm_prop)
              if not l.startswith('#') and '=' in l and not re.match(r'^dalvik.vm.heapsize=36m', l)]
    moved += [l for l in lines(sys_prop)
              if not l.startswith('#') and '=' in l and not PROP_BUILD_SKIP.match(l)]
    seen = set()
    block = []
    for l in moved:
        l = re.sub(r' *= *', '=', l.replace('/system/vendor/', '/vendor/'), count=1)
        k = l.split('=', 1)[0]
        if k not in seen:
            seen.add(k)
            block.append(l)
    for o in lines(open(os.path.join(props_dir, 'override.prop'), 'rb').read()):
        k = o.split('=', 1)[0]
        idx = [i for i, l in enumerate(block) if l.split('=', 1)[0] == k]
        if not idx:
            die('override.prop: %s not among the stock props' % k)
        block[idx[0]] = o
    out = stock.decode('utf-8', 'surrogateescape')
    if not out.endswith('\n'):
        out += '\n'
    out += open(os.path.join(props_dir, 'vndk.prop'), encoding='utf-8').read()
    out += PROP_HEADER + ''.join(l + '\n' for l in block)
    out += open(os.path.join(props_dir, 'append.prop'), encoding='utf-8').read()
    return out.encode('utf-8', 'surrogateescape')


def read_table(path):
    rows = []
    for line in open(path, encoding='utf-8'):
        if not line.strip() or line.startswith('#'):
            continue
        t = line.rstrip('\n').split('\t')
        if len(t) != 6:
            die('%s: bad line %r' % (path, line))
        rows.append(t)
    return rows


def ea_label(label):
    return {'security.selinux': label.encode() + b'\0'}


class Vendor:
    def __init__(self, a):
        self.a = a
        self.work = os.path.abspath(a.work)
        self.mod = os.path.join(self.work, 'mod')
        self.entries = {}

    def path_of(self, rel):
        return os.path.join(self.mod, rel)

    def mod_path(self, rel):
        p = self.path_of(rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        return p

    def extract(self):
        a = self.a
        rd = os.path.join(self.work, 'rdump')
        for d in (rd, self.mod):
            if os.path.exists(d):
                run(['chmod', '-R', 'u+rwX', d])
                shutil.rmtree(d)
            os.makedirs(d)
        print('mkvendor: extracting %s from %s' % (VENDOR, a.system))
        subprocess.run(['debugfs', '-R', 'rdump %s %s' % (VENDOR, rd), a.system],
                       capture_output=True, check=True)
        tree = os.path.join(rd, 'vendor')
        run(['chmod', '-R', 'u+rwX', tree])
        meta = e2meta.walk(a.system, VENDOR)
        if len(meta) < 1000:
            die('%s in %s has only %d entries; is this a stock system_a?' % (VENDOR, a.system, len(meta)))
        lower = {}
        for p in meta:
            lower.setdefault(p.lower(), []).append(p)
        twins = [v for v in lower.values() if len(v) > 1]
        if twins and os.path.exists(os.path.join(rd, 'VENDOR')):
            die('%s has names that differ only in case (%s) and %s is on a case-insensitive '
                'filesystem; use a case-sensitive --work directory' % (VENDOR, ', '.join(twins[0]), self.work))
        for p, (mode, uid, gid, eas) in meta.items():
            local = os.path.join(tree, p)
            if p and not os.path.lexists(local):
                die('rdump lost %s' % p)
            e = {'mode': mode, 'uid': uid, 'gid': gid, 'eas': dict(eas)}
            if stat.S_ISLNK(mode):
                e['link'] = os.readlink(local)
            elif stat.S_ISREG(mode):
                e['src'] = local
            self.entries[p] = e
        self.tree = tree

    def relabel(self):
        plat = os.path.join(self.work, 'plat_file_contexts')
        with open(plat, 'wb') as f:
            f.write(debugfs_cat(self.a.system, '/system/etc/selinux/plat_file_contexts'))
        fc = FileContexts([plat, os.path.join(self.tree, 'etc/selinux/vendor_file_contexts')])
        n = 0
        for p, e in self.entries.items():
            label = fc.lookup('/vendor/' + p if p else '/vendor', e['mode'])
            if label and ea_label(label)['security.selinux'] != e['eas'].get('security.selinux'):
                e['eas'].update(ea_label(label))
                n += 1
        print('mkvendor: %d stock entries relabelled from file_contexts' % n)

    def need(self, rel):
        e = self.entries.get(rel)
        if not e or 'src' not in e:
            die('stock vendor has no file %s' % rel)
        return e

    def patch_text(self):
        for diff in sorted(self.a.diffs):
            targets = re.findall(r'^\+\+\+ b/(\S+)', open(diff, encoding='utf-8').read(), re.M)
            for t in targets:
                e = self.need(t)
                dst = self.mod_path(t)
                shutil.copyfile(e['src'], dst)
                e['src'] = dst
            run(['patch', '-s', '-f', '-p1', '--no-backup-if-mismatch', '-d', self.mod, '-i',
                 os.path.abspath(diff)])
            print('mkvendor: applied %s' % os.path.relpath(diff, self.a.repo))

    def patch_props(self):
        e = self.need('build.prop')
        sysimg = self.a.system
        data = build_prop(open(e['src'], 'rb').read(),
                          debugfs_cat(sysimg, '/default.prop'),
                          debugfs_cat(sysimg, '/system/sdm660_64.prop'),
                          debugfs_cat(sysimg, '/system/build.prop'),
                          os.path.join(self.a.repo, 'vendor/props'))
        dst = self.mod_path('build.prop')
        with open(dst, 'wb') as f:
            f.write(data)
        e['src'] = dst

    def patch_xtra(self):
        e = self.need('bin/xtra-daemon')
        dst = self.mod_path('bin/xtra-daemon')
        run([sys.executable, os.path.join(os.path.dirname(__file__), 'bytepatch.py'),
             os.path.join(self.a.repo, 'vendor/xtra-daemon.bpatch'), e['src'], dst])
        e['src'] = dst

    def patch_gui(self):
        e = self.need('lib/libgui_vendor.so')
        dst = self.mod_path('lib/libgui_vendor.so')
        shutil.copyfile(e['src'], dst)
        run(shlex.split(self.a.patchelf) + ['--add-needed', 'libxp8shim.so', dst])
        e['src'] = dst

    def resign_cacert(self):
        rel = 'app/CACertService/CACertService.apk'
        e = self.need(rel)
        d = os.path.join(self.work, 'cacert')
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
        tmp, aligned, out = (os.path.join(d, n) for n in ('tmp.apk', 'aligned.apk', 'out.apk'))
        shutil.copyfile(e['src'], tmp)
        run(['zip', '-q', '-d', tmp, 'META-INF/*'])
        run(shlex.split(self.a.zipalign) + ['-f', '4', tmp, aligned])
        keys = self.a.keys
        run(shlex.split(self.a.apksigner) + [
            'sign', '--key', os.path.join(keys, 'platform.pk8'),
            '--cert', os.path.join(keys, 'platform.x509.pem'), '--out', out, aligned])
        dst = self.mod_path(rel)
        shutil.copyfile(out, dst)
        e['src'] = dst

    def apply_table(self):
        a = self.a
        for path, mode, uid, gid, label, source in read_table(os.path.join(a.repo, 'vendor/fs_config.tsv')):
            if source == 'remove':
                gone = [p for p in self.entries if p == path or p.startswith(path + '/')]
                if not gone:
                    die('nothing to remove at %s' % path)
                for p in gone:
                    del self.entries[p]
                continue
            meta = {'mode': int(mode, 8), 'uid': int(uid), 'gid': int(gid), 'eas': ea_label(label)}
            if source.startswith('stock:'):
                self.add_stock_libs(os.path.join(a.repo, source[6:]), meta)
                continue
            e = dict(meta)
            if source == '-':
                if not stat.S_ISDIR(e['mode']):
                    die('%s: no source for a non-directory' % path)
            elif source.startswith('repo:'):
                e['src'] = os.path.join(a.repo, source[5:])
            elif source.startswith('comp:'):
                e['src'] = os.path.join(a.components, source[5:])
            else:
                die('unknown source %r' % source)
            if 'src' in e and not os.path.isfile(e['src']):
                die('missing %s' % e['src'])
            if path in self.entries and path != 'lost+found':
                die('%s already exists in the stock vendor' % path)
            parent = os.path.dirname(path)
            if parent and parent not in self.entries:
                die('%s: parent directory not created first' % path)
            self.entries[path] = e

    def precompile_sepolicy(self):
        sp = os.path.join(self.a.components, 'sepolicy')
        secilc = shutil.which('secilc')
        sel = 'etc/selinux/'
        if not os.path.isdir(sp) or not secilc:
            print('mkvendor: no %s; the phone compiles its SELinux policy at boot'
                  % ('secilc' if secilc is None else 'components/sepolicy'))
            return
        # Mirrors init's secilc call; genfs label files would need init's version logic.
        if (sel + 'genfs_labels_version.txt' in self.entries
                or os.path.exists(os.path.join(sp, 'system/selinux/plat_sepolicy_genfs_202404.cil'))):
            print('mkvendor: genfs labels in use; the phone compiles its SELinux policy at boot')
            return
        vers = open(self.need(sel + 'plat_sepolicy_vers.txt')['src']).read().strip()

        def comp(*p):
            f = os.path.join(sp, *p)
            return [f] if os.path.isfile(f) else []

        plat, mapping = comp('system/selinux/plat_sepolicy.cil'), comp('system/selinux/mapping/%s.cil' % vers)
        if not plat or not mapping:
            die('components/sepolicy has no plat_sepolicy.cil or mapping/%s.cil' % vers)
        out = self.mod_path(sel + 'precompiled_sepolicy')
        args = [secilc, plat[0], '-m', '-M', 'true', '-G', '-N', '-c', '30', mapping[0],
                '-o', out, '-f', os.devnull]
        args += comp('system/selinux/mapping/%s.compat.cil' % vers)
        args += comp('system_ext/selinux/system_ext_sepolicy.cil')
        args += comp('system_ext/selinux/mapping/%s.cil' % vers)
        args += comp('system_ext/selinux/mapping/%s.compat.cil' % vers)
        args += comp('product/selinux/product_sepolicy.cil')
        args += comp('product/selinux/mapping/%s.cil' % vers)
        args += [self.need(sel + 'plat_pub_versioned.cil')['src'], self.need(sel + 'vendor_sepolicy.cil')['src']]
        run(args)
        pol = self.need(sel + 'precompiled_sepolicy')
        pol['src'] = out
        for part, name in (('system', 'plat'), ('system_ext', 'system_ext'), ('product', 'product')):
            rel = '%sprecompiled_sepolicy.%s_sepolicy_and_mapping.sha256' % (sel, name)
            src = comp('%s/selinux/%s_sepolicy_and_mapping.sha256' % (part, name))
            if not src:
                self.entries.pop(rel, None)
                continue
            e = self.entries.setdefault(rel, {k: pol[k] for k in ('mode', 'uid', 'gid')} | {'eas': dict(pol['eas'])})
            e['src'] = src[0]
        print('mkvendor: precompiled the SELinux policy (vendor mapping %s)' % vers)

    def add_stock_libs(self, listfile, meta):
        pairs = []
        for line in open(listfile, encoding='utf-8'):
            if not line.strip() or line.startswith('#'):
                continue
            dest, src = line.rstrip('\n').split('\t')
            if dest in self.entries:
                die('%s already exists in the stock vendor' % dest)
            pairs.append((dest, src))
        libdir = os.path.join(self.work, 'libs')
        shutil.rmtree(libdir, ignore_errors=True)
        debugfs_dump(self.a.system, [(s, os.path.join(libdir, d)) for d, s in pairs])
        for dest, _ in pairs:
            e = dict(meta)
            e['eas'] = dict(meta['eas'])
            e['src'] = os.path.join(libdir, dest)
            self.entries[dest] = e
        print('mkvendor: %d libraries from stock system_a' % len(pairs))

    def write_image(self):
        out = os.path.abspath(self.a.out)
        tmp = out + '.tmp'
        with open(tmp, 'wb') as f:
            f.truncate(IMAGE_BYTES)
        env = dict(os.environ, E2FSPROGS_FAKE_TIME=str(STAMP), SOURCE_DATE_EPOCH=str(STAMP))
        run(['mke2fs', '-q', '-F', '-t', 'ext4', '-b', '4096', '-L', 'vendor', '-M', '/vendor',
             '-m', '0', '-N', '65536', '-I', '256', '-J', 'size=32', '-O', MKE2FS_FEATURES,
             '-U', self.a.uuid, '-E', 'hash_seed=' + self.a.uuid, '-o', 'Linux', tmp], env=env)
        run(['tune2fs', '-o', 'acl,user_xattr', tmp], stdout=subprocess.DEVNULL, env=env)
        eadir = os.path.join(self.work, 'ea')
        shutil.rmtree(eadir, ignore_errors=True)
        os.makedirs(eadir)
        q = e2meta.q
        cmds = []
        cwd = None
        for p in sorted(self.entries):
            if p == '' or p == 'lost+found':
                continue
            e = self.entries[p]
            parent, name = '/' + os.path.dirname(p), os.path.basename(p)
            if parent != cwd:
                cmds.append('cd ' + q(parent))
                cwd = parent
            if stat.S_ISDIR(e['mode']):
                cmds.append('mkdir ' + q(name))
            elif stat.S_ISLNK(e['mode']):
                cmds.append('symlink %s %s' % (q(name), q(e['link'])))
            elif stat.S_ISREG(e['mode']):
                cmds.append('write %s %s' % (q(e['src']), q(name)))
            else:
                die('unsupported file type at %s' % p)
        cmds.append('cd /')
        for p in sorted(self.entries):
            e = self.entries[p]
            ip = q('/' + p)
            cmds += ['sif %s mode 0%o' % (ip, e['mode']), 'sif %s uid %d' % (ip, e['uid']),
                     'sif %s gid %d' % (ip, e['gid'])]
            cmds += ['sif %s %s @%d' % (ip, f, STAMP) for f in ('atime', 'ctime', 'mtime', 'crtime')]
            for name, val in sorted(e['eas'].items()):
                vf = os.path.join(eadir, hashlib.sha256(val).hexdigest())
                if not os.path.exists(vf):
                    with open(vf, 'wb') as f:
                        f.write(val)
                cmds.append('ea_set -f %s %s %s' % (q(vf), ip, name))
        print('mkvendor: writing %d entries' % len(self.entries))
        p = subprocess.run(['debugfs', '-w', '-f', '-', tmp], input=''.join(c + '\n' for c in cmds).encode(),
                           capture_output=True, env=env)
        err = [l for l in p.stderr.decode('utf-8', 'replace').splitlines() if not l.startswith('debugfs ')]
        if p.returncode or err:
            die('debugfs failed:\n' + '\n'.join(err[:20]))
        p = subprocess.run(['e2fsck', '-fn', tmp], capture_output=True, env=env)
        if p.returncode:
            die('e2fsck found errors:\n' + p.stdout.decode('utf-8', 'replace'))
        self.verify(tmp)
        os.replace(tmp, out)

    def verify(self, img):
        built = e2meta.walk(img, '/')
        want = self.entries
        bad = []
        for p in sorted(set(built) | set(want)):
            if p not in built or want.get(p) is None:
                bad.append('%s: %s' % (p or '/', 'missing' if p not in built else 'unexpected'))
                continue
            mode, uid, gid, eas = built[p]
            w = want[p]
            if (mode, uid, gid, eas) != (w['mode'], w['uid'], w['gid'], w['eas']):
                bad.append('%s: metadata differs' % (p or '/'))
        if bad:
            die('built image does not match the plan:\n' + '\n'.join(bad[:20]))
        print('mkvendor: image metadata verified (%d entries)' % len(built))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--system', required=True, help='raw stock system_a image')
    ap.add_argument('--components', required=True)
    ap.add_argument('--repo', required=True)
    ap.add_argument('--work', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--keys', required=True, help='dir with platform.pk8 and platform.x509.pem')
    ap.add_argument('--apksigner', default='apksigner')
    ap.add_argument('--zipalign', default='zipalign')
    ap.add_argument('--patchelf', default='patchelf')
    ap.add_argument('--uuid', default='58503847-5349-4000-8000-76656e646f72')
    a = ap.parse_args()
    a.repo = os.path.abspath(a.repo)
    a.components = os.path.abspath(a.components)
    vdir = os.path.join(a.repo, 'vendor')
    # vendor/audio diffs are applied on the phone at boot (vendor/xp8-vendor-patch.sh).
    a.diffs = [os.path.join(dp, f) for dp, _, fs in os.walk(vdir) for f in fs
               if f.endswith('.diff') and os.path.basename(dp) != 'audio']
    os.makedirs(a.work, exist_ok=True)
    v = Vendor(a)
    v.extract()
    v.relabel()
    v.patch_text()
    v.patch_props()
    v.patch_xtra()
    v.patch_gui()
    v.resign_cacert()
    v.apply_table()
    v.precompile_sepolicy()
    v.write_image()


if __name__ == '__main__':
    main()
