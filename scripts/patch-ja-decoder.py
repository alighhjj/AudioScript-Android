#!/usr/bin/env python3
"""日文多语种模型的语言标签补丁。

模型来自 PengChengStarling，训练时用 <JA>（token id = 6）替换 <SOS> 指定语种；
sherpa-onnx 的解码器首帧固定喂 [pad, <blk>]，官方明确表示不支持指定语言标签。
<blk> 只会出现在首帧，所以把 decoder embedding 第 0 行原地换成 <JA> 行，
首帧上下文就回到训练时的样子。

用法：
    python3 scripts/patch-ja-decoder.py <decoder.onnx> [lang_id]

成功退出码 0；文件结构不符、找不到 embedding 张量时退出码 1，且不修改文件。
"""

import sys

TENSOR = b"decoder.embedding.weight"


def varint(buf, i):
    result = shift = 0
    while True:
        byte = buf[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def fields(buf, start, end):
    i = start
    while i < end:
        key, i = varint(buf, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(buf, i)
            yield field, wire, value, i, i
        elif wire == 2:
            size, i = varint(buf, i)
            yield field, wire, (i, i + size), i, i + size
            i += size
        elif wire == 5:
            yield field, wire, None, i, i + 4
            i += 4
        elif wire == 1:
            yield field, wire, None, i, i + 8
            i += 8
        else:
            raise ValueError(f"不支持的 protobuf wire type: {wire}")


def patch(path, lang_id):
    buf = bytearray(open(path, "rb").read())
    graph = next((v for f, w, v, s, e in fields(buf, 0, len(buf)) if f == 7), None)
    if graph is None:
        return False

    for f, w, v, s, e in fields(buf, graph[0], graph[1]):
        if f != 5:  # GraphProto.initializer
            continue

        name = b""
        dims = []
        raw = None
        for f2, w2, v2, s2, e2 in fields(buf, v[0], v[1]):
            if f2 == 1:  # TensorProto.dims
                if w2 == 0:
                    dims.append(v2)
                else:
                    j = s2
                    while j < e2:
                        dim, j = varint(buf, j)
                        dims.append(dim)
            elif f2 == 8:  # TensorProto.name
                name = bytes(buf[s2:e2])
            elif f2 == 9:  # TensorProto.raw_data
                raw = (s2, e2)

        if name != TENSOR or raw is None or len(dims) != 2:
            continue

        rows, cols = dims
        row = cols * 4
        if lang_id < 0 or lang_id >= rows or raw[1] - raw[0] != rows * row:
            return False

        base = raw[0]
        buf[base:base + row] = buf[base + lang_id * row:base + (lang_id + 1) * row]
        open(path, "wb").write(buf)
        return True

    return False


def main():
    if len(sys.argv) < 2:
        print("用法：patch-ja-decoder.py <decoder.onnx> [lang_id]", file=sys.stderr)
        return 2

    path = sys.argv[1]
    lang_id = int(sys.argv[2]) if len(sys.argv) > 2 else 6

    try:
        ok = patch(path, lang_id)
    except Exception as error:  # noqa: BLE001 - 命令行工具，统一转成可读错误
        print(f"补丁失败：{error}", file=sys.stderr)
        return 1

    if not ok:
        print(f"补丁失败：{path} 里没有可改写的 {TENSOR.decode()}", file=sys.stderr)
        return 1

    print(f"patched: {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
