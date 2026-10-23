package J2;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import com.rtbishop.look4sat.feature.cw.WaterfallHistory;

/* loaded from: classes.dex */
public final class b implements GLSurfaceView.Renderer {

    /* renamed from: b, reason: collision with root package name */
    public FloatBuffer f729b;
    public int c;

    /* renamed from: d, reason: collision with root package name */
    public final Bitmap f730d;
    public final Context f;

    /* renamed from: i, reason: collision with root package name */
    public final a f734i;

    private final WaterfallHistory history;
    private final int[] uploadPixels;
    private long uploadedSequence = -1L;
    private int visibleColumns = 1;

    /* renamed from: a, reason: collision with root package name */
    public final int[] f728a = new int[1];

    /* renamed from: e, reason: collision with root package name */
    public final float[] f731e = {-1.0f, -1.0f, 0.0f, 1.0f, -1.0f, 1.0f, 0.0f, 0.0f, 1.0f, -1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f};

    public b(Context context, int i4) {
        Bitmap.Config config = Bitmap.Config.ARGB_8888;
        Bitmap createBitmap = Bitmap.createBitmap(2048, i4, config);
        this.f730d = createBitmap;
        this.f734i = new a();
        this.history = new WaterfallHistory(2048, i4, f734i.f727a);
        this.uploadPixels = new int[2048 * i4];
        this.f = context;
    }

    public static void a() {
        int glGetError = GLES20.glGetError();
        if (glGetError != 0) {
            StackTraceElement stackTraceElement = Thread.currentThread().getStackTrace()[3];
            Log.e(stackTraceElement.getFileName() + ":" + stackTraceElement.getLineNumber(), ", GLES20 error: " + glGetError);
        }
    }

    public boolean appendSpectrum(float[] spectrum) {
        return history.appendSpectrum(spectrum);
    }

    private void updateBitmap(long previousSequence) {
        long sequence = history.copyIfChanged(previousSequence, uploadPixels);
        if (sequence != previousSequence) {
            f730d.setPixels(uploadPixels, 0, f730d.getWidth(), 0, 0, f730d.getWidth(), f730d.getHeight());
        }
        uploadedSequence = sequence;
    }

    public final void c(GL10 gl10) {
        GLES20.glUseProgram(this.c);
        a();
        GLES20.glActiveTexture(33984);
        a();
        GLES20.glBindTexture(3553, this.f728a[0]);
        a();
        GLES20.glUniform1i(GLES20.glGetUniformLocation(this.c, "uTexture"), 0);
        a();
        int glGetAttribLocation = GLES20.glGetAttribLocation(this.c, "aPosition");
        a();
        int glGetAttribLocation2 = GLES20.glGetAttribLocation(this.c, "aTexPos");
        a();
        this.f729b.position(0);
        a();
        GLES20.glVertexAttribPointer(glGetAttribLocation, 2, 5126, false, 16, (Buffer) this.f729b);
        a();
        GLES20.glEnableVertexAttribArray(glGetAttribLocation);
        a();
        this.f729b.position(2);
        a();
        GLES20.glVertexAttribPointer(glGetAttribLocation2, 2, 5126, false, 16, (Buffer) this.f729b);
        a();
        GLES20.glEnableVertexAttribArray(glGetAttribLocation2);
        a();
        GLES20.glDrawArrays(5, 0, 4);
        a();
    }

    public final void d(GL10 gl10, int i4, int i5) {
        GLES20.glViewport(0, 0, i4, i5);
        a();
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onDrawFrame(GL10 gl10) {
        long previousSequence = uploadedSequence;
        updateBitmap(previousSequence);
        if (previousSequence != uploadedSequence) {
            GLES20.glBindTexture(3553, f728a[0]);
            GLUtils.texSubImage2D(3553, 0, 0, 0, f730d);
        }
        float right = (history.nextColumn(uploadedSequence) - 0.5f) / f730d.getWidth();
        float left = right - (float) visibleColumns / f730d.getWidth();
        float[] fArr = this.f731e;
        fArr[6] = left;
        fArr[2] = left;
        fArr[14] = right;
        fArr[10] = right;
        this.f729b.position(0);
        this.f729b.put(this.f731e);
        c(gl10);
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onSurfaceChanged(GL10 gl10, int i4, int i5) {
        d(gl10, i4, i5);
        visibleColumns = Math.max(1, Math.min(f730d.getWidth(),
            Math.round(i4 / f.getResources().getDisplayMetrics().density / 0.7f)));
    }

    @Override // android.opengl.GLSurfaceView.Renderer
    public final void onSurfaceCreated(GL10 gl10, EGLConfig eGLConfig) {
        int glCreateShader = GLES20.glCreateShader(35633);
        a();
        GLES20.glShaderSource(glCreateShader, "attribute vec2 aPosition;\nattribute vec2 aTexPos;\nvarying vec2 vTexPos;\nvoid main() {\n  vTexPos = aTexPos;\n  gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n}");
        a();
        GLES20.glCompileShader(glCreateShader);
        a();
        int glCreateShader2 = GLES20.glCreateShader(35632);
        a();
        GLES20.glShaderSource(glCreateShader2, "precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vTexPos;\nvoid main(void)\n{\n  gl_FragColor = texture2D(uTexture, vTexPos);\n}");
        a();
        GLES20.glCompileShader(glCreateShader2);
        a();
        this.c = GLES20.glCreateProgram();
        a();
        GLES20.glAttachShader(this.c, glCreateShader);
        a();
        GLES20.glAttachShader(this.c, glCreateShader2);
        a();
        GLES20.glLinkProgram(this.c);
        a();
        int[] iArr = this.f728a;
        GLES20.glGenTextures(1, iArr, 0);
        a();
        GLES20.glBindTexture(3553, iArr[0]);
        a();
        GLES20.glTexParameteri(3553, 10240, 9729);
        GLES20.glTexParameteri(3553, 10242, 10497);
        GLES20.glTexParameteri(3553, 10241, 9729);
        GLES20.glTexParameteri(3553, 10243, 10497);
        a();
        updateBitmap(-1L);
        GLUtils.texImage2D(3553, 0, this.f730d, 0);
        a();
        float[] fArr = this.f731e;
        ByteBuffer allocateDirect = ByteBuffer.allocateDirect(fArr.length * 4);
        allocateDirect.order(ByteOrder.nativeOrder());
        FloatBuffer asFloatBuffer = allocateDirect.asFloatBuffer();
        this.f729b = asFloatBuffer;
        asFloatBuffer.put(fArr);
    }
}
