package com.vanta.studio.engine.effects

/** GLSL ES 1.00 sources. Every declared uniform is used, as Media3's GlProgram requires. */
internal object Shaders {
    const val VERTEX_COPY = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    const val VERTEX_TRANSFORM = """
attribute vec4 aFramePosition;
uniform mat4 uTransform;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransform * aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

    private const val PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

    /**
     * Colour grading and stylised effects. Uniform packing:
     *  uBasic = brightness, contrast, saturation, exposure
     *  uTone  = temperature, tint, shadows, highlights
     *  uLook  = vignette, sharpen, grain, sepia
     *  uStyle = black&white, teal/orange, fade, blur
     *  uFx    = pixelate, glitch, chromatic aberration, mirror
     *  uFx2   = scanlines, light leak
     */
    const val FRAGMENT_GRADE = PRECISION + """
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uTime;
uniform vec4 uBasic;
uniform vec4 uTone;
uniform vec4 uLook;
uniform vec4 uStyle;
uniform vec4 uFx;
uniform vec2 uFx2;
varying vec2 vTexSamplingCoord;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float luma(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec4 sampleBlur(vec2 uv, float radius) {
  if (radius <= 0.0) {
    return texture2D(uTexSampler, uv);
  }
  vec4 sum = vec4(0.0);
  float total = 0.0;
  for (int x = -2; x <= 2; x++) {
    for (int y = -2; y <= 2; y++) {
      vec2 o = vec2(float(x), float(y));
      float w = 1.0 - length(o) / 3.5;
      sum += texture2D(uTexSampler, uv + o * uTexel * radius) * w;
      total += w;
    }
  }
  return sum / total;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  if (uFx.w > 0.5 && uv.x > 0.5) {
    uv.x = 1.0 - uv.x;
  }
  if (uFx.x > 0.0) {
    float cells = mix(220.0, 18.0, uFx.x);
    vec2 grid = vec2(cells, cells * uTexel.x / uTexel.y);
    uv = (floor(uv * grid) + 0.5) / grid;
  }
  if (uFx.y > 0.0) {
    float t = floor(uTime * 12.0);
    float band = floor(uv.y * 18.0);
    if (hash(vec2(band, t)) > 1.0 - 0.4 * uFx.y) {
      uv.x += (hash(vec2(t, band + 7.0)) - 0.5) * 0.14 * uFx.y;
    }
  }
  vec4 src = sampleBlur(uv, uStyle.w * 7.0);
  vec3 c = src.rgb;

  float split = uFx.z * 0.012 + uFx.y * 0.007;
  if (split > 0.0) {
    c.r = texture2D(uTexSampler, uv + vec2(split, 0.0)).r;
    c.b = texture2D(uTexSampler, uv - vec2(split, 0.0)).b;
  }

  if (uLook.y > 0.0) {
    vec3 n = texture2D(uTexSampler, uv + vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, uv - vec2(uTexel.x, 0.0)).rgb
           + texture2D(uTexSampler, uv + vec2(0.0, uTexel.y)).rgb
           + texture2D(uTexSampler, uv - vec2(0.0, uTexel.y)).rgb;
    c = c + (c - n * 0.25) * uLook.y * 2.0;
  }

  c *= pow(2.0, uBasic.w * 1.5);
  c += vec3(uBasic.x * 0.25);
  c.r += uTone.x * 0.12;
  c.b -= uTone.x * 0.12;
  c.g -= uTone.y * 0.10;
  c.r += uTone.y * 0.04;
  c.b += uTone.y * 0.04;

  float l = luma(clamp(c, 0.0, 1.0));
  c += vec3(uTone.z * 0.25 * (1.0 - smoothstep(0.0, 0.5, l)));
  c += vec3(uTone.w * 0.25 * smoothstep(0.5, 1.0, l));

  c = (c - 0.5) * (1.0 + uBasic.y) + 0.5;
  l = luma(c);
  c = mix(vec3(l), c, 1.0 + uBasic.z);

  if (uStyle.y > 0.0) {
    l = luma(clamp(c, 0.0, 1.0));
    vec3 teal = vec3(-0.07, 0.03, 0.09);
    vec3 orange = vec3(0.10, 0.03, -0.08);
    c += mix(teal, orange, smoothstep(0.2, 0.8, l)) * uStyle.y;
  }

  vec3 sep = vec3(
    dot(c, vec3(0.393, 0.769, 0.189)),
    dot(c, vec3(0.349, 0.686, 0.168)),
    dot(c, vec3(0.272, 0.534, 0.131)));
  c = mix(c, sep, uLook.w);
  c = mix(c, vec3(luma(c)), uStyle.x);
  c = c * (1.0 - 0.25 * uStyle.z) + vec3(0.12 * uStyle.z);

  if (uFx2.x > 0.0) {
    float line = sin(vTexSamplingCoord.y / uTexel.y * 1.5708);
    c *= 1.0 - uFx2.x * 0.3 * (0.5 + 0.5 * line);
  }
  if (uFx2.y > 0.0) {
    vec2 p = vTexSamplingCoord - vec2(0.18 + 0.12 * sin(uTime * 0.7), 0.78 + 0.1 * cos(uTime * 0.5));
    float g = exp(-dot(p, p) * 3.5);
    c += vec3(1.0, 0.55, 0.25) * g * uFx2.y * 0.9;
  }
  if (uLook.x > 0.0) {
    float d = length(vTexSamplingCoord - 0.5) * 1.25;
    c *= mix(1.0, 1.0 - smoothstep(0.35, 0.95, d), uLook.x);
  }
  if (uLook.z > 0.0) {
    float n = hash(vTexSamplingCoord * 997.0 + vec2(fract(uTime * 7.3) * 100.0, 0.0)) - 0.5;
    c += vec3(n * uLook.z * 0.2);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""

    /**
     * Clip placement and transitions.
     *  uMix  = opacity, darken (to black), whiten (to white), blur
     *  uWipe = edge position (negative = no wipe), 1 = reveal left side / 0 = hide left side
     */
    const val FRAGMENT_MOTION = PRECISION + """
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform vec4 uMix;
uniform vec2 uWipe;
varying vec2 vTexSamplingCoord;

void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 col;
  if (uMix.w > 0.0) {
    vec4 sum = vec4(0.0);
    float r = uMix.w * 18.0;
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        sum += texture2D(uTexSampler, uv + vec2(float(x), float(y)) * uTexel * r);
      }
    }
    col = sum / 25.0;
  } else {
    col = texture2D(uTexSampler, uv);
  }
  vec3 c = col.rgb * col.a * uMix.x;
  c = mix(c, vec3(0.0), uMix.y);
  c = mix(c, vec3(1.0), uMix.z);
  if (uWipe.x >= 0.0) {
    bool visible = uWipe.y > 0.5 ? uv.x < uWipe.x : uv.x >= uWipe.x;
    if (!visible) {
      c = vec3(0.0);
    }
  }
  gl_FragColor = vec4(c, 1.0);
}
"""

    /**
     * Light events (flashes, glows, flares, glitch...) evaluated by [com.vanta.studio.domain.fx.LightMath].
     *  uFlash = flash colour rgb, amount
     *  uGlowA = glow, bloom, radial burst, radial radius
     *  uGlowB = light leak, lens flare, flare x, flare y
     *  uSweep = sweep amount, sweep position, motion blur, (unused)
     *  uDist  = chromatic aberration, glitch, invert, exposure (stops)
     *  uMisc  = saturation pulse, zoom, shake x, shake y
     */
    const val FRAGMENT_LIGHT = PRECISION + """
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uTime;
uniform vec4 uFlash;
uniform vec4 uGlowA;
uniform vec4 uGlowB;
uniform vec4 uSweep;
uniform vec3 uLightRgb;
uniform vec4 uDist;
uniform vec4 uMisc;
varying vec2 vTexSamplingCoord;

float hash(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
  float aspect = uTexel.y / uTexel.x;
  vec2 p = (vTexSamplingCoord - 0.5) / uMisc.y + 0.5 + vec2(uMisc.z, uMisc.w);
  if (uDist.y > 0.0) {
    float t = floor(uTime * 20.0);
    float band = floor(p.y * 14.0);
    if (hash(vec2(band, t)) > 1.0 - 0.55 * min(uDist.y, 1.0)) {
      p.x += (hash(vec2(t, band + 3.0)) - 0.5) * 0.18 * uDist.y;
    }
  }
  vec4 src = texture2D(uTexSampler, p);
  vec3 c = src.rgb;

  if (uSweep.z > 0.0) {
    vec3 sum = vec3(0.0);
    for (int i = -4; i <= 4; i++) {
      sum += texture2D(uTexSampler, p + vec2(float(i) * uTexel.x * uSweep.z * 7.0, 0.0)).rgb;
    }
    c = sum / 9.0;
  }
  float split = uDist.x * 0.012 + uDist.y * 0.008;
  if (split > 0.0) {
    c.r = texture2D(uTexSampler, p + vec2(split, 0.0)).r;
    c.b = texture2D(uTexSampler, p - vec2(split, 0.0)).b;
  }

  c *= pow(2.0, uDist.w);
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, 1.0 + uMisc.x);

  if (uGlowA.x + uGlowA.y > 0.0) {
    vec3 soft = vec3(0.0);
    vec3 bright = vec3(0.0);
    float total = 0.0;
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        vec2 o = vec2(float(x), float(y));
        float w = 1.0 - length(o) / 3.2;
        vec3 s = texture2D(uTexSampler, p + o * uTexel * 9.0).rgb;
        soft += s * w;
        bright += max(s - 0.55, 0.0) * 2.2 * w;
        total += w;
      }
    }
    c += (soft / total) * uGlowA.x * 0.6 * uLightRgb;
    c += (bright / total) * uGlowA.y * 1.8 * uLightRgb;
  }
  if (uGlowA.z > 0.0) {
    vec2 q = vTexSamplingCoord - 0.5;
    q.x *= aspect;
    float d = length(q);
    float r = max(uGlowA.w, 0.05);
    float core = exp(-d * d / (r * r * 0.25));
    float ring = exp(-pow((d - r * 0.55) * 7.0, 2.0)) * 0.6;
    c += uLightRgb * (core + ring) * uGlowA.z * 0.9;
  }
  if (uGlowB.x > 0.0) {
    vec2 q = vTexSamplingCoord - vec2(0.08 + 0.06 * sin(uTime * 2.3), 0.88);
    q.x *= aspect;
    float g = exp(-dot(q, q) * 2.6);
    vec2 q2 = vTexSamplingCoord - vec2(0.95, 0.15 + 0.05 * cos(uTime * 1.7));
    q2.x *= aspect;
    g += 0.6 * exp(-dot(q2, q2) * 4.0);
    c += uLightRgb * g * uGlowB.x * 0.9;
  }
  if (uGlowB.y > 0.0) {
    vec2 f = vec2(uGlowB.z * aspect, uGlowB.w);
    vec2 q = vec2(vTexSamplingCoord.x * aspect, vTexSamplingCoord.y);
    vec2 ctr = vec2(0.5 * aspect, 0.5);
    float core = exp(-dot(q - f, q - f) * 260.0);
    float streak = exp(-abs(q.y - f.y) * 110.0) * exp(-abs(q.x - f.x) * 2.5) * 0.55;
    float ghosts = 0.0;
    for (int i = 1; i <= 3; i++) {
      vec2 gp = mix(f, ctr * 2.0 - f, float(i) * 0.38);
      ghosts += exp(-pow((length(q - gp) - 0.05 * float(i)) * 22.0, 2.0)) * (0.35 / float(i));
    }
    c += uLightRgb * (core + streak + ghosts) * uGlowB.y;
  }
  if (uSweep.x > 0.0) {
    float d = (vTexSamplingCoord.x + vTexSamplingCoord.y * 0.4) - (uSweep.y * 1.8 - 0.4);
    c += uLightRgb * exp(-d * d * 60.0) * uSweep.x * 0.9;
  }
  c = mix(c, vec3(1.0) - c, uDist.z);
  c = mix(c, uFlash.rgb, uFlash.a);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
}
