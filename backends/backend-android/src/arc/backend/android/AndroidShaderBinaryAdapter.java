package arc.backend.android;

import arc.*;
import arc.graphics.gl.*;
import arc.util.*;

import java.nio.*;

/**
 * {@link ShaderBinaryCache} adapter for the Android GLES backend.
 * Calls android.opengl.GLES30 entry points directly instead of going through
 * arc.graphics.Gl, so the GL20/GL30 interface matrix of every other backend
 * stays untouched.
 * Installed automatically by {@link AndroidApplication} when a GLES 3.0+ context
 * is active; the cache stays inert on GLES 2.0-only devices.
 */
public class AndroidShaderBinaryAdapter implements ShaderBinaryCache.Adapter{
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_PROGRAM_BINARY_LENGTH = 0x8741;
    private static final int GL_PROGRAM_BINARY_FORMATS = 0x87FF;
    private static final int GL_NUM_PROGRAM_BINARY_FORMATS = 0x87FE;
    private static boolean formatsLogged;

    private static final AndroidShaderBinaryAdapter instance = new AndroidShaderBinaryAdapter();

    public static void install(){
        ShaderBinaryCache.adapter = instance;
    }

    @Override
    public boolean supported(){
        //requires a GL30 context; program binaries are core in ES3
        return Core.gl30 != null;
    }

    @Override
    public int binaryLength(int program){
        int[] buf = new int[1];
        android.opengl.GLES30.glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, buf, 0);
        return buf[0];
    }

    @Override
    public boolean getBinary(int program, int[] format, ByteBuffer buf){
        buf.position(0);
        int[] length = new int[1];
        int[] fmt = new int[1];
        android.opengl.GLES30.glGetProgramBinary(program, buf.capacity(), length, 0, fmt, 0, buf);
        if(length[0] <= 0) return false;
        buf.limit(length[0]);
        format[0] = fmt[0];
        return true;
    }

    @Override
    public boolean install(int program, int format, ByteBuffer binary){
        //binary.position must already be at the raw data (past any header)
        android.opengl.GLES30.glProgramBinary(program, format, binary, binary.remaining());
        //must verify link status: the driver may reject a binary for any reason
        int[] buf = new int[1];
        android.opengl.GLES30.glGetProgramiv(program, GL_LINK_STATUS, buf, 0);
        int linkStatus = buf[0];
        int glError = android.opengl.GLES30.glGetError();
        if(linkStatus == 0 || glError != 0){
            int[] errBuf = new int[1];
            android.opengl.GLES30.glGetProgramiv(program, GL_PROGRAM_BINARY_LENGTH, errBuf, 0);
            Log.infoTag("shader-cache", "install failed: link=" + linkStatus + " glError=0x" + Integer.toHexString(glError) +
                " format=0x" + Integer.toHexString(format) + " len=" + binary.remaining() +
                " progBinaryLen=" + errBuf[0]);
            if(!formatsLogged){
                formatsLogged = true;
                logSupportedFormats();
            }
        }
        return linkStatus != 0 && glError == 0;
    }

    private static void logSupportedFormats(){
        try{
            int[] num = new int[1];
            android.opengl.GLES30.glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS, num, 0);
            if(num[0] <= 0){
                Log.infoTag("shader-cache", "driver reports 0 program binary formats");
                return;
            }
            int[] formats = new int[num[0]];
            android.opengl.GLES30.glGetIntegerv(GL_PROGRAM_BINARY_FORMATS, formats, 0);
            StringBuilder sb = new StringBuilder("supported binary formats (").append(num[0]).append("): ");
            for(int f : formats){
                sb.append("0x").append(Integer.toHexString(f)).append(" ");
            }
            Log.infoTag("shader-cache", sb.toString());
        }catch(Throwable t){
            Log.errTag("shader-cache", "failed to query binary formats: " + t);
        }
    }

    @Override
    public void deleteProgram(int program){
        Core.gl.glDeleteProgram(program);
    }
}
