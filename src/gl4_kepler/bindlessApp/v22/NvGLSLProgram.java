//----------------------------------------------------------------------------------
// File:        gl4-kepler/BindlessApp/BindlessApp.cpp
// SDK Version: v2.11 
// Email:       gameworks@nvidia.com
// Site:        http://developer.nvidia.com/
//
// Copyright (c) 2014-2015, NVIDIA CORPORATION. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//  * Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
//  * Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
//  * Neither the name of NVIDIA CORPORATION nor the names of its
//    contributors may be used to endorse or promote products derived
//    from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY
// EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
// PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
// CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
// EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
// PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
// OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
//----------------------------------------------------------------------------------
package gl4_kepler.bindlessApp.v22;

import static com.jogamp.opengl.GL2ES2.GL_FRAGMENT_SHADER;
import static com.jogamp.opengl.GL2ES2.GL_VERTEX_SHADER;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.glsl.ShaderCode;
import com.jogamp.opengl.util.glsl.ShaderProgram;

/**
 *
 * @author elect
 */
public class NvGLSLProgram {

    public int programName;
    private boolean logAllMissing = false;
    private boolean strict;

    private NvGLSLProgram(int program, boolean strict) {
        this.programName = program;
        this.strict = strict;
    }

    public static NvGLSLProgram createFromFiles(GL4 gl4, String root, String shaderName) {
        return createFromFiles(gl4, root, shaderName, false);
    }

    public static NvGLSLProgram createFromFiles(GL4 gl4, String root, String shaderName, boolean strict) {

        ShaderProgram shaderProgram = new ShaderProgram();

        ShaderCode vertShaderCode = ShaderCode.create(gl4, GL_VERTEX_SHADER, NvGLSLProgram.class, root, null,
                shaderName, "vert", null, true);
        ShaderCode fragShaderCode = ShaderCode.create(gl4, GL_FRAGMENT_SHADER, NvGLSLProgram.class, root, null,
                shaderName, "frag", null, true);

        shaderProgram.add(vertShaderCode);
        shaderProgram.add(fragShaderCode);

        shaderProgram.init(gl4);

        shaderProgram.link(gl4, System.out);

        return new NvGLSLProgram(shaderProgram.program(), strict);
    }

    public int getAttribLocation(GL4 gl4, String attribute) {
        return getAttribLocation(gl4, attribute, false);
    }

    public int getAttribLocation(GL4 gl4, String attribute, boolean isOptional) {

        int result = gl4.glGetAttribLocation(programName, attribute);

        if (result == -1) {
            if ((logAllMissing || strict) && !isOptional) {
                System.err.println("could not find attribute " + attribute + " in program " + programName);
            }
        }

        return result;
    }

    public void enable(GL4 gl4) {
        gl4.glUseProgram(programName);
    }

    public void disable(GL4 gl4) {
        gl4.glUseProgram(0);
    }

    public int getUniformLocation(GL4 gl4, String uniform) {
        return getUniformLocation(gl4, uniform, false);
    }

    public int getUniformLocation(GL4 gl4, String uniform, boolean isOptional) {

        int result = gl4.glGetUniformLocation(programName, uniform);

        if (result == -1) {
            if ((logAllMissing || strict) && !isOptional) {
                System.err.println("could not find uniform " + uniform + " in program " + programName);
            }
        }

        return result;
    }
}
