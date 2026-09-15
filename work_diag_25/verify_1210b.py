# -*- coding: utf-8 -*-
"""DLsiteFloat 1.21.0 APK 指纹校验（icon 阴影改为底部投影 / card-on-table drop shadow）

沿用 v1.20.16 的坑：
- arsc 字符串池不恒定编码（ASCII→UTF-8，中文→UTF-16LE），两种都要试；
- dex 里 `+` 拼接的日志串只剩片段，探测串必须是**源码里真实存在的字面量**。
"""
import zipfile
import sys
import os

APK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   '..', 'app', 'build', 'outputs', 'apk', 'debug',
                   'DLsiteFloat-1.21.0-debug.apk')

# ── dex 里必须存在的串 ──
DEX_PRESENT = [
    '1.21.0',
    ' BUILD 1.21.0',                   # v41b：新 BUILD 行
    'card-on-table drop shadow',       # v41b：BUILD 描述片段（底部投影）
    'outer shadow offset down+right',  # v41b：BUILD 描述片段
    ' subtitle scroll: cue ',          # v40：新增位移动画诊断行
    ' travel=',
    'px scrollDelta=',
    'applyAnimatedCenter',
    'startTravelAnim',
    'cueBlockRange',
    'cueBlockFrom',
    'pendingTravelFromCue',
    'page follow resume:',
    ' catch-up ',
    'px after ',
    'syncFollowOffsetOnShow',
    'FOLLOW_MAX_HELD_MS',
    'page follow channel: transform-sum active',
    'page follow bias calibrated: ',
    'hide suppressed: page held (vis=',
    ' snap=',
    ' attached=',
    ' still=',
    ': button translationY=',
    'com.sena.dlsitesoundfloat',
    'jp.co.eisys.dlsitesound.MainActivity',
    'LottieAnimationView',
]
# ── dex 里必须**不存在**的旧版串（防「装错包 / 增量残留」）──
DEX_ABSENT = [
    '==== BUILD 1.20.16',
    '==== BUILD 1.20.15',
    '==== BUILD 1.20.14',
    'outer lifted shadow only',        # v41 旧描述，本版已改
]
# ── resources.arsc 里必须存在的串（两种编码都试）──
RES_PRESENT = [
    'DLsiteFloat',
]
# ── AndroidManifest.xml(二进制 XML) 里必须存在的串 ──
MANIFEST_PRESENT = [
    'DLSite Sound 字幕悬浮窗插件',
    'xposeddescription',
    'jp.co.eisys.dlsitesound',
]


def in_blob(blob, s):
    """串在字节流里出现过吗（UTF-8 与 UTF-16LE 都试）。"""
    for enc in ('utf-8', 'utf-16-le'):
        try:
            if s.encode(enc) in blob:
                return True
        except Exception:
            pass
    return False


def main():
    if not os.path.exists(APK):
        print('APK NOT FOUND:', APK)
        return 2

    z = zipfile.ZipFile(APK)
    names = z.namelist()

    dex_blobs = b''.join(z.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
    arsc_blobs = b''.join(z.read(n) for n in names if n.endswith('.arsc'))
    manifest_blobs = b''.join(z.read(n) for n in names if n == 'AndroidManifest.xml')
    print('apk        : %s' % os.path.basename(APK))
    print('apk size   : %d bytes' % os.path.getsize(APK))
    print('dex files  : %s' % [n for n in names if n.endswith('.dex')])
    print('dex bytes  : %d' % len(dex_blobs))
    print('arsc bytes : %d' % len(arsc_blobs))
    print('mani bytes : %d' % len(manifest_blobs))
    print()

    fail = 0
    print('--- dex PRESENT ---')
    for s in DEX_PRESENT:
        ok = in_blob(dex_blobs, s)
        print('  %-4s %s' % ('OK' if ok else 'MISS', s[:78]))
        fail += 0 if ok else 1

    print('--- dex ABSENT (must not appear) ---')
    for s in DEX_ABSENT:
        ok = not in_blob(dex_blobs, s)
        print('  %-4s %s' % ('OK' if ok else 'LEAK', s[:78]))
        fail += 0 if ok else 1

    print('--- resources.arsc PRESENT ---')
    for s in RES_PRESENT:
        ok = in_blob(arsc_blobs, s)
        print('  %-4s %s' % ('OK' if ok else 'MISS', s[:78]))
        fail += 0 if ok else 1

    print('--- AndroidManifest PRESENT ---')
    for s in MANIFEST_PRESENT:
        ok = in_blob(manifest_blobs, s)
        print('  %-4s %s' % ('OK' if ok else 'MISS', s[:78]))
        fail += 0 if ok else 1

    print()
    print('RESULT: %s' % ('ALL PASS' if fail == 0 else '%d CHECK(S) FAILED' % fail))
    return 0 if fail == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
