import struct
import sys

def convert_bin_to_csv(bin_path, csv_path):
    with open(bin_path, 'rb') as f_in, open(csv_path, 'w') as f_out:
        f_out.write("timestampNs,x,y,z\n")
        # Structure is likely 8 bytes (long) + 4 bytes (float) * 3 = 20 bytes
        while True:
            chunk = f_in.read(20)
            if len(chunk) < 20:
                break
            ts, x, y, z = struct.unpack('<qfff', chunk)
            f_out.write(f"{ts},{x},{y},{z}\n")

if __name__ == '__main__':
    convert_bin_to_csv(sys.argv[1], sys.argv[2])
