package arc.graphics.gl;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.util.*;

import java.nio.*;
import java.security.MessageDigest;

/**
 * Optional fast path for {@link Shader} construction: stores compiled GL program
 * binaries on disk and reloads them with glProgramBinary instead of recompiling
 * GLSL from source on every application start.
 * <p>
 * Backend opt-in: a backend with access to the glGetProgramBinary entry points
 * (e.g. backend-android via GLES30) installs an adapter through
 * {@link #adapter}. If no adapter is installed, the cache is inert and shaders
 * compile from source as before.
 * <p>
 * Cache keys are derived from the exact preprocessed shader source pair plus
 * the driver identity (GL_VERSION + GL_RENDERER), so a driver or source change
 * silently misses and falls back to a normal compile. Every failure mode
 * (missing file, format mismatch, glProgramBinary rejection) falls back to
 * source compilation; the cache can never make shader loading fail.
 */
public class ShaderBinaryCache{
    /** Installed by a backend that can call glGetProgramBinary/glProgramBinary. Null = disabled. */
    public static volatile Adapter adapter;
    /** Global on/off switch; when false, load() always misses and save() is a no-op. */
    public static volatile boolean enabled = true;

    private ShaderBinaryCache(){
    }

    /** True if a backend adapter is present and the cache directory is usable. */
    public static boolean enabled(){
        Adapter a = adapter;
        return a != null && Core.files != null;
    }

    /**
     * Attempts to load a cached program binary for this source pair.
     * @return a linked program object handle, or -1 on any miss/failure (caller compiles from source).
     */
    public static int load(String vertexShader, String fragmentShader){
        if(!enabled) return -1;
        Adapter a = adapter;
        if(a == null || !a.supported()) return -1;
        try{
            Fi file = fileFor(vertexShader, fragmentShader);
            if(file == null || !file.exists()) return -1;

            byte[] bytes = file.readBytes();
            if(bytes.length < 8) return -1;

            ByteBuffer buf = ByteBuffer.allocateDirect(bytes.length);
            buf.put(bytes);
            buf.position(0);

            int format = buf.getInt();
            int length = buf.getInt();
            if(length <= 0 || length > bytes.length - 8) return -1;
            buf.limit(8 + length);
            buf.position(8); //skip header: glProgramBinary expects raw binary data

            int program = Gl.createProgram();
            if(program == 0) return -1;
            if(!a.install(program, format, buf)){
                a.deleteProgram(program);
                Log.infoTag("shader-cache", "driver rejected binary, recompiling");
                return -1;
            }
            Log.infoTag("shader-cache", "hit: " + file.name());
            return program;
        }catch(Throwable t){
            Log.errTag("shader-cache", "load failed, falling back to compile: " + t);
            return -1;
        }
    }

    /**
     * Stores the binary of a freshly compiled + linked program.
     * Never throws; storage failures are logged and ignored.
     */
    public static void save(int program, String vertexShader, String fragmentShader){
        if(!enabled) return;
        Adapter a = adapter;
        if(a == null || !a.supported() || program == 0 || program == -1) return;
        try{
            Fi file = fileFor(vertexShader, fragmentShader);
            if(file == null) return;

            int[] fmt = {0};
            int length = a.binaryLength(program);
            if(length <= 0) return;

            ByteBuffer buf = ByteBuffer.allocateDirect(length);
            if(!a.getBinary(program, fmt, buf)) return;

            int format = fmt[0];
            ByteBuffer out = ByteBuffer.allocate(8 + length);
            out.putInt(format).putInt(length);
            buf.position(0);
            buf.limit(length);
            out.put(buf);
            file.writeBytes(out.array(), false);
            Log.infoTag("shader-cache", "stored: " + file.name());
        }catch(Throwable t){
            Log.errTag("shader-cache", "save failed (ignored): " + t);
        }
    }

    /** Cache file for this exact source pair + driver. Null if the environment can't provide one. */
    private static Fi fileFor(String vertexShader, String fragmentShader){
        try{
            String version = Gl.getString(GL20.GL_VERSION) + "/" + Gl.getString(GL20.GL_RENDERER);
            if(version == null || version.isEmpty()) return null;
            String key = sha256(version + "\u0000" + vertexShader + "\u0000" + fragmentShader);
            Fi dir = Core.files.local("shader_cache");
            dir.mkdirs();
            return dir.child(key + ".bin");
        }catch(Throwable t){
            return null;
        }
    }

    private static String sha256(String s){
        MessageDigest digest;
        try{
            digest = MessageDigest.getInstance("SHA-256");
        }catch(Exception e){
            return Integer.toHexString(s.hashCode());
        }
        byte[] hash;
        try{
            hash = digest.digest(s.getBytes("UTF-8"));
        }catch(Exception e){
            hash = digest.digest(s.getBytes());
        }
        StringBuilder b = new StringBuilder(64);
        for(byte x : hash) b.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return b.toString();
    }

    /** Backend-specific entry points for program binary retrieval and installation. */
    public interface Adapter{
        /** Whether the current GL context actually exposes program binaries. */
        boolean supported();

        /** Buffer size needed for {@link #getBinary} for this program (GL_PROGRAM_BINARY_LENGTH). */
        int binaryLength(int program);

        /** Retrieves the binary; must fill {@code format[0]} and {@code buf}. Returns false on failure. */
        boolean getBinary(int program, int[] format, ByteBuffer buf);

        /** Links {@code program} from a stored binary. Returns false if the driver rejected it. */
        boolean install(int program, int format, ByteBuffer binary);

        void deleteProgram(int program);
    }
}
