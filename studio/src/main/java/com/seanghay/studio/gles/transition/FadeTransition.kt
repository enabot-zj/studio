package com.seanghay.studio.gles.transition

import com.seanghay.studio.gles.transition.Transition

class FadeTransition(name: String, duration: Long) : Transition(
    name, SOURCE, duration
) {

    companion object {
        // language=glsl
        const val SOURCE = """
            

            vec4 transition(vec2 uv) {
                return mix(
                    getFromColor(uv),
                    getToColor(uv),
                    progress);
            }
        """
    }
}

