package site.gehlot.rattan

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The layer behind all views: the background photo and every glass panel, drawn by one shader.
 * It renders only when the panels change (layout), so a still home screen costs nothing. Focus is
 * shown by the views on top, which keeps it in step with the remote.
 */
class StageView(context: Context, private val onFailure: () -> Unit) : GLSurfaceView(context) {
    private val renderer = StageRenderer(context.resources) { post(onFailure) }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        // Keeps the uploaded photo while an app is open, so coming back home needs no reload.
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** [panels] holds, per panel: centre x, centre y (GL, y up), half width, half height, corner radius. */
    fun show(panels: FloatArray) {
        queueEvent { renderer.panels = panels }
        requestRender()
    }
}

private class StageRenderer(private val res: Resources, private val onFailure: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var panels: FloatArray? = null

    private var program = 0
    private var scene = 0
    private var frost = 0
    private var width = 0f
    private var height = 0f
    private val rects = FloatArray(4 * MAX_PANELS)
    private val looks = FloatArray(4 * MAX_PANELS)
    private var uSize = 0
    private var uCount = 0
    private var uRect = 0
    private var uLook = 0

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        if (program == 0) {
            onFailure()
            return
        }
        scene = texture(R.drawable.wallpaper)
        frost = texture(R.drawable.wallpaper_frost)
        uSize = GLES30.glGetUniformLocation(program, "uSize")
        uCount = GLES30.glGetUniformLocation(program, "uCount")
        uRect = GLES30.glGetUniformLocation(program, "uRect")
        uLook = GLES30.glGetUniformLocation(program, "uLook")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uScene"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFrost"), 1)
        // The vertex shader makes its own full-screen triangle; it only needs a bound vertex array.
        val vao = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
    }

    override fun onDrawFrame(unused: GL10?) {
        val current = panels
        if (program == 0 || current == null) {
            // Nothing until the panels are known, so they never pop in after the background.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val count = minOf(current.size / 5, MAX_PANELS)
        for (i in 0 until count) {
            System.arraycopy(current, i * 5, rects, i * 4, 4)
            looks[i * 4] = current[i * 5 + 4]
        }
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uSize, width, height)
        GLES30.glUniform1i(uCount, count)
        GLES30.glUniform4fv(uRect, MAX_PANELS, rects, 0)
        GLES30.glUniform4fv(uLook, MAX_PANELS, looks, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, scene)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frost)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /** Uploads an image and frees the Java copy right away; only the GPU keeps it. */
    private fun texture(id: Int): Int {
        val bitmap = BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
        val name = IntArray(1)
        GLES30.glGenTextures(1, name, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return name[0]
    }

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        if (vs == 0 || fs == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "link failed: " + GLES30.glGetProgramInfoLog(p))
            return 0
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader failed: " + GLES30.glGetShaderInfoLog(s))
            return 0
        }
        return s
    }

    private companion object {
        const val TAG = "RattanStage"
        const val MAX_PANELS = 12

        const val VERTEX = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        // Each panel is a rounded rectangle of glass. Near its rim the surface curves away, so what
        // lies behind is bent outward (the lens), split slightly by colour, and catches the light.
        // The flat middle shows the background lightly frosted. Outside, a soft shadow falls below.
        const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uScene;
uniform sampler2D uFrost;
uniform vec2 uSize;
uniform int uCount;
uniform vec4 uRect[12];  // centre (GL pixels, y up), half size
uniform vec4 uLook[12];  // corner radius
out vec4 outColor;

const vec2 LIGHT = vec2(-0.6, 0.8);

float box(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

vec2 uv(vec2 px) { return vec2(px.x, uSize.y - px.y) / uSize; }

void main() {
    vec2 px = gl_FragCoord.xy;
    vec3 color = texture(uScene, uv(px)).rgb;

    float shade = 0.0;
    for (int i = 0; i < uCount; i++) {
        float d = box(px - uRect[i].xy + vec2(0.0, 12.0), uRect[i].zw, uLook[i].x);
        shade = max(shade, 0.6 * (1.0 - smoothstep(-8.0, 34.0, d)));
    }
    color *= 1.0 - shade;

    for (int i = 0; i < uCount; i++) {
        vec2 c = uRect[i].xy;
        vec2 hs = uRect[i].zw;
        float radius = uLook[i].x;
        vec2 p = px - c;
        float d = box(p, hs, radius);
        if (d > 1.0) continue;

        vec2 e = vec2(1.0, 0.0);
        vec2 n = vec2(box(p + e.xy, hs, radius) - box(p - e.xy, hs, radius),
                      box(p + e.yx, hs, radius) - box(p - e.yx, hs, radius));
        n = n / max(length(n), 1e-4);
        float depth = max(-d, 0.0);
        float t = clamp(depth / min(radius, 26.0), 0.0, 1.0);
        float edge = (1.0 - t) * (1.0 - t);

        vec2 sp = c + p * 0.97 + n * edge * 34.0;
        float split = edge * 3.0;
        vec3 sharp = vec3(texture(uScene, uv(sp + n * split)).r,
                          texture(uScene, uv(sp)).g,
                          texture(uScene, uv(sp - n * split)).b);
        vec3 soft = texture(uFrost, uv(sp)).rgb;
        vec3 g = mix(sharp, soft, 0.45 * t);
        float luma = dot(g, vec3(0.2126, 0.7152, 0.0722));
        g = mix(vec3(luma), g, 1.25) * 1.18 + 0.02;
        g = mix(g, vec3(1.0), 0.05);

        float facing = dot(n, normalize(LIGHT));
        float rim = 1.0 - smoothstep(0.0, 1.8, depth);
        float glint = edge * (0.32 * max(facing, 0.0) + 0.16 * max(-facing, 0.0));
        g += (rim * (0.30 + 0.45 * abs(facing)) + glint) * 0.85;
        g += 0.04 * smoothstep(-hs.y, hs.y, p.y);

        color = mix(color, g, 1.0 - smoothstep(-0.75, 0.75, d));
    }
    outColor = vec4(color, 1.0);
}
"""
    }
}
