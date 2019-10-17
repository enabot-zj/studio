/**
 * Designed and developed by Seanghay Yath (@seanghay)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seanghay.studio.gles.transition

import com.seanghay.studio.gles.graphics.uniform.uniform1f
import com.seanghay.studio.gles.graphics.uniform.uniform1i

class DoomScreenTransitionTransition : Transition("doom-screen-transition", SOURCE, 1000L) {

    open var bars: Int = 30
    open var barsUniform = uniform1i("bars").autoInit()
    open var amplitude: Float = 2f
    open var amplitudeUniform = uniform1f("amplitude").autoInit()
    open var noise: Float = 0.1f
    open var noiseUniform = uniform1f("noise").autoInit()
    open var frequency: Float = 0.5f
    open var frequencyUniform = uniform1f("frequency").autoInit()
    open var dripScale: Float = 0.5f
    open var dripScaleUniform = uniform1f("dripScale").autoInit()

    override fun onUpdateUniforms() {
        super.onUpdateUniforms()

        barsUniform.setValue(bars)
        amplitudeUniform.setValue(amplitude)
        noiseUniform.setValue(noise)
        frequencyUniform.setValue(frequency)
        dripScaleUniform.setValue(dripScale)
    }

    companion object {
        // language=glsl
        const val SOURCE = """
// Author: Zeh Fernando
// License: MIT


// Transition parameters --------

// Number of total bars/columns
uniform int bars; // = 30

// Multiplier for speed ratio. 0 = no variation when going down, higher = some elements go much faster
uniform float amplitude; // = 2

// Further variations in speed. 0 = no noise, 1 = super noisy (ignore frequency)
uniform float noise; // = 0.1

// Speed variation horizontally. the bigger the value, the shorter the waves
uniform float frequency; // = 0.5

// How much the bars seem to "run" from the middle of the screen first (sticking to the sides). 0 = no drip, 1 = curved drip
uniform float dripScale; // = 0.5


// The code proper --------

float rand(int num) {
  return fract(mod(float(num) * 67123.313, 12.0) * sin(float(num) * 10.3) * cos(float(num)));
}

float wave(int num) {
  float fn = float(num) * frequency * 0.1 * float(bars);
  return cos(fn * 0.5) * cos(fn * 0.13) * sin((fn+10.0) * 0.3) / 2.0 + 0.5;
}

float drip(int num) {
  return sin(float(num) / float(bars - 1) * 3.141592) * dripScale;
}

float pos(int num) {
  return (noise == 0.0 ? wave(num) : mix(wave(num), rand(num), noise)) + (dripScale == 0.0 ? 0.0 : drip(num));
}

vec4 transition(vec2 uv) {
  int bar = int(uv.x * (float(bars)));
  float scale = 1.0 + pos(bar) * amplitude;
  float phase = progress * scale;
  float posY = uv.y / vec2(1.0).y;
  vec2 p;
  vec4 c;
  if (phase + posY < 1.0) {
    p = vec2(uv.x, uv.y + mix(0.0, vec2(1.0).y, phase)) / vec2(1.0).xy;
    c = getFromColor(p);
  } else {
    p = uv.xy / vec2(1.0).xy;
    c = getToColor(p);
  }

  // Finally, apply the color
  return c;
}

        """
    }
}
