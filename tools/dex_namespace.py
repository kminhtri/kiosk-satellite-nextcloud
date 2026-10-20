"""Check the final DEX type references, rather than trusting the classfile build."""
import struct


def verify_namespace(data):
    if data[:4] != b'dex\n':
        raise ValueError('Not a DEX file')
    strings_size, strings_off, types_size, types_off = struct.unpack_from('<IIII', data, 56)

    def string(index):
        offset, = struct.unpack_from('<I', data, strings_off + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        return data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace')

    types = [string(struct.unpack_from('<I', data, types_off + i * 4)[0]) for i in range(types_size)]
    shared = ('Lkotlin/', 'Lokhttp3/', 'Lokio/', 'Lorg/jetbrains/')
    leaked = [name for name in types if name.lstrip('[').startswith(shared)]
    if leaked:
        raise ValueError('Unisolated DEX dependency references: ' + ', '.join(leaked[:10]))
    count, offset = struct.unpack_from('<II', data, 96)
    for i in range(count):
        index, = struct.unpack_from('<I', data, offset + 32 * i)
        if not types[index].startswith('Lio/github/kiosknextcloud/'):
            raise ValueError('Unexpected bundled class: ' + types[index])
    return count
