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

open class CrazyParametricFunTransition : Transition("crazy-parametric-fun", SOURCE, 1000L) {

    open var a: Float = 4f
    open var aUniform = uniform1f("a").autoInit()
    open var b: Float = 1f
    open var bUniform = uniform1f("b").autoInit()
    open var amplitude: Float = 120f
    open var amplitudeUniform = uniform1f("amplitude").autoInit()
    open var smoothness: Float = 0.1f
    open var smoothnessUniform = uniform1f("smoothness").autoInit()

    override fun onUpdateUniforms() {
        super.onUpdateUniforms()

        aUniform.setValue(a)
        bUniform.setValue(b)
        amplitudeUniform.setValue(amplitude)
        smoothnessUniform.setValue(smoothness)
    }

    companion object {
        // language=glsl
        const val SOURCE = """
// Author: mandubian
// License: MIT

uniform float a; // = 4
uniform float b; // = 1
uniform float amplitude; // = 120
uniform float smoothness; // = 0.1

vec4 transition(vec2 uv) {
  vec2 p = uv.xy / vec2(1.0).xy;
  vec2 dir = p - vec2(.5);
  float dist = length(dir);
  float x = (a - b) * cos(progress) + b * cos(progress * ((a / b) - 1.) );
  float y = (a - b) * sin(progress) - b * sin(progress * ((a / b) - 1.));
  vec2 offset = dir * vec2(sin(progress  * dist * amplitude * x), sin(progress * dist * amplitude * y)) / smoothness;
  return mix(getFromColor(p + offset), getToColor(p), smoothstep(0.2, 1.0, progress));
}

        """
    }
}
