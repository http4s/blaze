package org.http4s.websocket;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

public class FrameTranscoder {

    // Masks for extracting fields
    final static int OP_CODE = WebsocketBits.OP_CODE();
    final static int FINISHED = WebsocketBits.FINISHED();
    final static int MASK = WebsocketBits.MASK();
    final static int LENGTH = WebsocketBits.LENGTH();
    //    public final static int RESERVED = 0xe;
//
//    // message op codes
//    public final static int CONTINUATION = 0x0;
//    public final static int TEXT = 0x1;
//    public final static int BINARY = 0x2;
    final static int CLOSE = WebsocketBits.CLOSE();
    final static int PING = WebsocketBits.PING();
    final static int PONG = WebsocketBits.PONG();

    public final static class TranscodeError extends Exception {
        public TranscodeError(String message) {
            super(message);
        }
    }

    private final boolean isClient;

    public FrameTranscoder(boolean client) {
        isClient = client;
    }

    public ByteBuffer[] frameToBuffer(WebsocketBits.WebSocketFrame in) throws TranscodeError {
        int size = 2;

        if (isClient) size += 4;   // for the mask

        if (in.length() < 126){ /* NOOP */ }
        else if (in.length() <= 0xffff) size += 2;
        else size += 8;

        final ByteBuffer buff = ByteBuffer.allocate(isClient ? size + in.length() : size);

        final int opcode = in.opcode();

        if (in.length() > 125 && (opcode == PING || opcode == PONG || opcode == CLOSE))
            throw new TranscodeError("Invalid PING frame: frame too long: " + in.length());

        // First byte. Finished, reserved, and OP CODE
        byte b1 = (byte)opcode;
        if (in.last()) b1 |= FINISHED;

        buff.put(b1);

        // Second byte. Mask bit and length
        byte b2 = 0x0;

        if (isClient) b2 = (byte)MASK;

        if (in.length() < 126) b2 |= in.length();
        else if (in.length() <= 0xffff) b2 |= 126;
        else b2 |= 127;

        buff.put(b2);

        // Put the length if we have an extended length packet
        if (in.length() > 125 && in.length() <= 0xffff) {
            buff.put((byte)(in.length() >>> 8 & 0xff))
                    .put((byte)(in.length() & 0xff));
        }
        else if (in.length() > 0xffff) buff.putLong(in.length());

        // If we are a client, we need to mask the data, else just wrap it in a buffer and done
        if (isClient && in.length() > 0) {  // need to mask outgoing bytes
            int mask = (int)(Math.random()*Integer.MAX_VALUE);
            byte[] maskBits = {(byte)((mask >>> 24) & 0xff),
                    (byte)((mask >>> 16) & 0xff),
                    (byte)((mask >>>  8) & 0xff),
                    (byte)((mask >>>  0) & 0xff)};

            buff.put(maskBits);

            final byte[] data = in.data();

            for(int i = 0; i < in.length(); i++) {
                buff.put((byte)(data[i] ^ maskBits[i & 0x3])); // i & 0x3 is the same as i % 4 but faster
            }
            buff.flip();
            return new ByteBuffer[] { buff };
        } else {
            buff.flip();
            return new ByteBuffer[]{ buff, ByteBuffer.wrap(in.data()) };
        }
    }

    /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
     * Any unused data in the ByteBuffer will be recycled and available for the next read
     * @param in ByteBuffer of immediately available data
     * @return optional message if enough data was available
     */
    public WebsocketBits.WebSocketFrame bufferToFrame(final ByteBuffer in) throws TranscodeError, ProtocolException {

        if (in.remaining() < 2) return null;

        int len = getMsgLength(in);

        if (len < 0) return null;

        final int opcode = in.get(0) & OP_CODE;
        final boolean finished = (in.get(0) & FINISHED) != 0;
        final boolean masked = (in.get(1) & MASK) != 0;

        if (masked && isClient) throw new TranscodeError("Client received a masked message");

        byte[] m = null;

        int bodyOffset = lengthOffset(in);

        if (masked) {
            bodyOffset += 4;
            m = getMask(in);
        }

        final int oldLim = in.limit();
        final int bodylen = bodyLength(in);

        in.position(bodyOffset);
        in.limit(in.position() + bodylen);

        final ByteBuffer slice = in.slice();

        in.position(in.limit());
        in.limit(oldLim);

        return WebsocketBits.makeFrame(opcode, decodeBinary(slice, m), finished);
    }

    static private byte[] decodeBinary(ByteBuffer in, byte[] mask) {

        byte[] data = new byte[in.remaining()];
        in.get(data);

        if (mask != null) {  // We can use the charset decode
            for(int i = 0; i < data.length; i++) {
                data[i] = (byte)(data[i] ^ mask[i & 0x3]);   // i mod 4 is the same as i & 0x3 but slower
            }
        }
        return data;                                                                                               }

    static private int lengthOffset(ByteBuffer in) throws TranscodeError {
        int len = in.get(1) & LENGTH;

        if (len < 126) return 2;
        else if (len == 126) return 4;
        else if (len == 127) return 10;
        else throw new TranscodeError("Length error!");
    }

    static private byte[] getMask(ByteBuffer in) throws TranscodeError {
        byte[] m = new byte[4];
        in.mark();
        in.position(lengthOffset(in));
        in.get(m);
        in.reset();
        return m;
    }

    static private int bodyLength(ByteBuffer in) throws TranscodeError {
        final int len = in.get(1) & LENGTH;

        if (len < 126)       return len;
        else if (len == 126) return (in.get(2) << 8 & 0xff00) | (in.get(3) & 0xff);
        else if (len == 127) {
            long l = in.getLong(2);
            if (l > Integer.MAX_VALUE) throw new TranscodeError("Frame is too long");
            else return (int)l;
        }
        else throw new TranscodeError("Length error");
    }

    static private int getMsgLength(ByteBuffer in) throws TranscodeError {
        int totalLen = 2;
        if ((in.get(1) & MASK) != 0) totalLen += 4;

        int len = in.get(1) & LENGTH;

        if (len == 126) totalLen += 2;
        if (len == 127) totalLen += 8;

        if (in.remaining() < totalLen) return -1;

        totalLen += bodyLength(in);

        if (in.remaining() < totalLen) return -1;
        else return totalLen;
    }
}
