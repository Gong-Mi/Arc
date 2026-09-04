package arc.util;

public class ArcNativesLoader{
    public static boolean disableNativesLoading = false;
    public static boolean loaded;

    /** Loads the arc native libraries if they have not already been loaded. */
    public static synchronized void load(){
        if(loaded) return;
        loaded = true;

        if(disableNativesLoading) return;

        try{
            new SharedLibraryLoader().load("arc");
        }catch(Throwable t){
            //natives unavailable (wrong platform, missing glibc, etc.) —
            //pure-Java fallbacks (PngReader, non-native Pixmap buffers) handle everything
            loaded = false;
            Log.err("Natives not loaded, using pure-Java fallbacks (@)", t);
        }
    }
}
