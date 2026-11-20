package uk.co.violetscale.probe;

/** Broadcast variant with the Yoda0-specific product structure. */
final class WeighFrame {
    final int sequence;
    final int centikg;

    private WeighFrame(int sequence, int centikg) {
        this.sequence = sequence;
        this.centikg = centikg;
    }

    static WeighFrame parse(byte[] record) {
        if (record == null) return null;
        boolean yoda = false;
        int field = -1;
        for (int pos = 0; pos < record.length;) {
            int length = record[pos++] & 255;
            if (length == 0) {
                while (pos < record.length) if (record[pos++] != 0) return null;
                break;
            }
            if (length > record.length - pos) return null;
            int type = record[pos] & 255;
            if (type == 0xff) {
                if (length != 16 || field != -1) return null;
                field = pos + 1;
            } else if (type == 0x09) {
                if (yoda || length != 6 || record[pos+1] != 'Y' || record[pos+2] != 'o'
                        || record[pos+3] != 'd' || record[pos+4] != 'a' || record[pos+5] != '0') return null;
                yoda = true;
            }
            pos += length;
        }
        if (!yoda || field == -1 || (record[field] & 255) != 0xc0
                || record[field+6] != 0 || record[field+7] != 0) return null;
        for (int i = 9; i < 15; i++) if (record[field+i] != 0) return null;
        int weight = ((record[field+2] & 255) << 8) | (record[field+3] & 255);
        int flag = record[field+8] & 255;
        if (flag == 0x24 && weight == 0 && record[field+4] == 0 && record[field+5] == 0)
            return new WeighFrame(record[field+1] & 255, 0);
        if (flag == 0x25 && weight > 0 && weight < 0xffff)
            return new WeighFrame(record[field+1] & 255, weight);
        // Bytes 4–5 are deliberately ignored for readings. Their meaning has not been verified for this variant.
        return null;
    }
}
