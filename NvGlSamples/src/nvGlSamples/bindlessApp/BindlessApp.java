/**
 * ----------------------------------------------------------------------------------
 * File: gl4-kepler/BindlessApp/BindlessApp.cpp SDK Version: v2.11 Email:
 * gameworks@nvidia.com Site: http://developer.nvidia.com/
 *
 * Copyright (c) 2014-2015, NVIDIA CORPORATION. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of NVIDIA CORPORATION nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * ----------------------------------------------------------------------------------
 */
package nvGlSamples.bindlessApp;

import nvGlSamples.bindlessApp.util.PerMeshUniforms;
import nvGlSamples.bindlessApp.util.Mesh;
import com.jogamp.newt.awt.NewtCanvasAWT;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.Animator;
import com.jogamp.opengl.util.GLBuffers;
import glsl.GLSLProgramObject;
import glutil.ViewPole;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import jglm.Jglm;
import jglm.Mat4;
import jglm.Vec2;
import jglm.Vec2i;
import jglm.Vec3;
import jogamp.graph.font.typecast.ot.table.Program;
import nvGlSamples.bindlessApp.util.TransformUniforms;
import nvGlSamples.bindlessApp.util.Vertex;
import nvGlSamples.util.NvImage;
import nvGlSamples.util.NvInputTransformer;

/**
 * This sample demonstrates using bindless rendering with
 * GL_NV_shader_buffer_load and GL_NV_vertex_buffer_unified_memory.
 * GL_NV_shader_buffer_load allows the program to pass a GPU pointer to the
 * vertex shader to load uniforms directly from GPU memory.
 * GL_NV_vertex_buffer_unified_memory allows the program to use GPU pointers to
 * vertex and index data when making rendering calls. Both of these extensions
 * can significantly reduce CPU L2 cache misses and pollution; this can
 * dramatically speed up scenes with large numbers of draw calls.
 *
 * For more detailed information see
 * http://www.nvidia.com/object/bindless_graphics.html *
 *
 * Interesting pieces of code are annotated with "*** INTERESTING ***"
 *
 * The interesting code in this sample is in this file and Mesh.cpp
 *
 * Mesh::update() in Mesh.cpp contains the source code for getting the GPU
 * pointers for vertex and index data
 *
 * Mesh::renderPrep() in Mesh.cpp sets up the vertex format
 *
 * Mesh::render() in Mesh.cpp does the actual rendering
 *
 * Mesh::renderFinish() in Mesh.cpp resets related state
 */
/**
 *
 * @author gbarbieri
 */
public class BindlessApp implements GLEventListener {

    private Vec2i imageSize;
    private GLWindow glWindow;
    private NewtCanvasAWT newtCanvasAWT;
    private Animator animator;
    private ViewPole viewPole;
    private int[] ubo;
    private MouseListener mouseListener;
    public static float projectionBase;
    private Scene scene;
    private NvInputTransformer transformer;

    private final int SQRT_BUILDING_COUNT = 100;

    // Simple collection of meshes to render
    private Mesh[] meshes;

    // Shader stuff
    private GLSLProgramObject shader;
    private int bindlessPerMeshUniformsPtrAttribLocation;

    // uniform buffer object (UBO) for tranform data
    private int[] transformUniforms;
    private TransformUniforms transformUniformsData;
    private Mat4 projectionMatrix;

    // uniform buffer object (UBO) for mesh param data
    private int[] perMeshUniforms;
    private PerMeshUniforms[] perMeshUniformsData;
    private long[] perMeshUniformsGPUPtr;

    // UI stuff
    private boolean usePerMeshUniforms;
    private boolean useBindlessUniforms;
    private boolean updateUniformsEveryFrame;

    //bindless texture handle
    private long[] textureHandles;
    private int[] textureIds;
    private final int TEXTURE_FRAME_COUNT = 181;
    private boolean useBindlessTextures;
    private int currentFrame;
    private float currentTime;

    private final String assetTextures = "/nvGlSamples/bindlessApp/assets/textures/";
    private final String assetShaders = "/nvGlSamples/bindlessApp/assets/shaders/";

    // Timing related stuff
    private long frameTime;
    private float minimumFrameDeltaTime;
    private float t;

    public BindlessApp() {

        imageSize = new Vec2i(1024, 768);

        initGL();

        useBindlessUniforms = true;
        useBindlessTextures = false;

        minimumFrameDeltaTime = 1e6f;
        t = 0f;
    }

    private void initGL() {

        GLProfile gLProfile = GLProfile.getDefault();

        GLCapabilities gLCapabilities = new GLCapabilities(gLProfile);

        glWindow = GLWindow.create(gLCapabilities);

        newtCanvasAWT = new NewtCanvasAWT(glWindow);

        glWindow.setSize(imageSize.x, imageSize.y);

        glWindow.addGLEventListener(this);

        animator = new Animator(glWindow);
        animator.start();
    }

    @Override
    public void init(GLAutoDrawable glad) {
        System.out.println("init");

        GL4 gl4 = glad.getGL().getGL4();

        // Check extensions; exit on failure
        checkExtenstions(gl4);

        // Create our pixel and vertex shader
        shader = new GLSLProgramObject(gl4, assetShaders, new String[]{"simple_vertex.glsl"},
                new String[]{"simple_fragment.glsl"});
        bindlessPerMeshUniformsPtrAttribLocation = gl4.glGetUniformLocation(
                shader.getProgramId(), "bindlessPerMeshUniformsPtr");

        // Set the initial view
        transformer.setRotationVec(new Vec3((float) Math.toRadians(30f),
                (float) Math.toRadians(30f), 0));

        // Create the meshes
        meshes = new Mesh[1 + SQRT_BUILDING_COUNT * SQRT_BUILDING_COUNT];
        perMeshUniformsData = new PerMeshUniforms[meshes.length];

        // Create a mesh for the ground
        meshes[0] = createGround(gl4, new Vec3(0f, -.001f, 0f), new Vec3(5f, 0f, 5f));

        // Create "building" meshes
        int meshIndex = 0;
        for (int i = 0; i < SQRT_BUILDING_COUNT; i++) {

            for (int k = 0; k < SQRT_BUILDING_COUNT; k++) {

                float x, y, z;
                float size;

                x = (float) i / (float) SQRT_BUILDING_COUNT - .5f;
                y = 0f;
                z = (float) k / (float) SQRT_BUILDING_COUNT - .5f;
                size = .025f * (100f / (float) SQRT_BUILDING_COUNT);

                Vec3 pos = new Vec3(5f * x, y, 5f * z);
                Vec3 dim = new Vec3(size, .2f + .1f * (float) Math.sin(5f * i * k), size);
                Vec2 uv = new Vec2((float) k / (float) SQRT_BUILDING_COUNT,
                        (float) i / (float) SQRT_BUILDING_COUNT);
                meshes[meshIndex + 1] = createBuilding(gl4, pos, dim, uv);

                meshIndex++;
            }
        }

        // Initialize Bindless Textures
        initBindlessTextures(gl4);

        // create Uniform Buffer Object (UBO) for transform data and initialize 
        transformUniforms = new int[1];
        gl4.glGenBuffers(1, transformUniforms, 0);
        gl4.glNamedBufferData(transformUniforms[0], TransformUniforms.size(), null,
                GL4.GL_STATIC_DRAW);

        // create Uniform Buffer Object (UBO) for param data and initialize
        perMeshUniforms = new int[1];
        gl4.glGenBuffers(1, perMeshUniforms, 0);

        // Initialize the per mesh Uniforms
        updatePerMeshUniforms(gl4, 0f);

//        Vec3 target = new Vec3(0f, .12495125f, 0f);
//        Quat orient = new Quat(0.0f, 0.0f, 0.0f, 1.0f);
//        ViewData initialViewData = new ViewData(target, orient, 0.5f, 0.0f);
//
//        ViewScale viewScale = new ViewScale(3.0f, 20.0f, 1.5f, 0.0005f, 0.0f, 0.0f, 90.0f / 250.0f);
//
//        viewPole = new ViewPole(initialViewData, viewScale, ViewPole.Projection.perspective);
//
//        mouseListener = new MouseListener(viewPole);
//        glWindow.addMouseListener(mouseListener);
//
//        int blockBinding = 0;
//
//        initUBO(gl4, blockBinding);
//
////        weightedBlended = new WeightedBlended(gl4, blockBinding);
//        gl4.glDisable(GL4.GL_CULL_FACE);
//
//        projectionBase = 5000f;
//
//        animator.setUpdateFPSFrames(60, System.out);
        checkError(gl4);

        frameTime = System.currentTimeMillis();
    }

    private void checkExtenstions(GL4 gl4) {

        boolean GL_NV_vertex_buffer_unified_memory = gl4.isExtensionAvailable("GL_NV_vertex_buffer_unified_memory");
        if (!GL_NV_vertex_buffer_unified_memory) {
            System.out.println("GL_NV_vertex_buffer_unified_memory not available");
        }
        boolean GL_NV_shader_buffer_load = gl4.isExtensionAvailable("GL_NV_shader_buffer_load");
        if (!GL_NV_shader_buffer_load) {
            System.out.println("GL_NV_shader_buffer_load not available");
        }
        boolean GL_EXT_direct_state_access = gl4.isExtensionAvailable("GL_EXT_direct_state_access");
        if (!GL_EXT_direct_state_access) {
            System.out.println("GL_EXT_direct_state_access not available");
        }
        boolean GL_NV_bindless_texture = gl4.isExtensionAvailable("GL_NV_bindless_texture");
        if (!GL_NV_bindless_texture) {
            System.out.println("GL_NV_bindless_texture not available");
        }
    }

    /**
     * Create a mesh for the ground
     *
     * @param gl4
     * @param pos
     * @param dim
     * @return
     */
    private Mesh createGround(GL4 gl4, Vec3 pos, Vec3 dim) {

        Mesh ground = new Mesh();

        ArrayList<Vertex> vertices = new ArrayList<>();
        ArrayList<Short> indices = new ArrayList<>();
        float r, g, b;

        dim.x *= .5f;
        dim.z *= .5f;

        // +Y face
        r = 0.3f;
        g = 0.3f;
        b = 0.3f;
        vertices.add(new Vertex(-dim.x + pos.x, pos.y, +dim.z + pos.z, r, g, b, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, pos.y, +dim.z + pos.z, r, g, b, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, pos.y, -dim.z + pos.z, r, g, b, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, pos.y, -dim.z + pos.z, r, g, b, 1f));

        // Create the indices
        indices.add((short) 0);
        indices.add((short) 1);
        indices.add((short) 2);
        indices.add((short) 0);
        indices.add((short) 2);
        indices.add((short) 3);

        ground.update(gl4, vertices, indices);

        return ground;
    }

    /**
     * Create a very simple building mesh
     *
     * @param gl4
     * @param pos
     * @param dim
     * @param uv
     * @return
     */
    private Mesh createBuilding(GL4 gl4, Vec3 pos, Vec3 dim, Vec2 uv) {

        Mesh building = new Mesh();

        ArrayList<Vertex> vertices = new ArrayList<>();
        ArrayList<Short> indices = new ArrayList<>();

        dim.x *= .5f;
        dim.y *= .5f;

        // Generate a simple building model (i.e. a box). All of the "buildings" are in world space
        // +Z face
        Vec3 color = randomColor();
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // -Z face
        color = randomColor();
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // +X face
        color = randomColor();
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // -X face
        color = randomColor();
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // +Y face
        color = randomColor();
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // -Y face
        color = randomColor();
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, -dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(+dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));
        vertices.add(new Vertex(-dim.x + pos.x, 0f + pos.y, +dim.z + pos.z,
                color.x, color.y, color.z, 1f));

        // Create the indices
        for (int i = 0; i < 24; i += 4) {
            indices.add((short) (0 + i));
            indices.add((short) (1 + i));
            indices.add((short) (2 + i));

            indices.add((short) (0 + i));
            indices.add((short) (2 + i));
            indices.add((short) (3 + i));
        }
        building.update(gl4, vertices, indices);

        return building;
    }

    /**
     * Generates a random color
     *
     * @return
     */
    private Vec3 randomColor() {

        float r = (float) Math.random();
        float g = (float) Math.random();
        float b = (float) Math.random();

        return new Vec3(r, g, b);
    }

    private void initBindlessTextures(GL4 gl4) {

        String filename = assetTextures;

        textureHandles = new long[TEXTURE_FRAME_COUNT];
        textureIds = new int[TEXTURE_FRAME_COUNT];

        for (int i = 0; i < TEXTURE_FRAME_COUNT; i++) {

            try {
                textureIds[i] = NvImage.uploadTextureFromDDSFile(gl4, filename);
            } catch (IOException ex) {
                Logger.getLogger(BindlessApp.class.getName()).log(Level.SEVERE, null, ex);
            }
            gl4.glBindTexture(GL4.GL_TEXTURE_2D, textureIds[i]);
            gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MAG_FILTER, GL4.GL_NEAREST);
            gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MIN_FILTER, GL4.GL_NEAREST);
            gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_S, GL4.GL_REPEAT);
            gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_T, GL4.GL_REPEAT);

            textureHandles[i] = gl4.glGetTextureHandleARB(textureIds[i]);
            gl4.glMakeTextureHandleResidentARB(textureHandles[i]);
        }
    }

    /**
     * Computes per mesh uniforms based on t and sends the uniforms to the GPU
     *
     * @param gl4
     * @param t
     */
    private void updatePerMeshUniforms(GL4 gl4, float t) {
        // If we're using per mesh uniforms, compute the values for the uniforms 
        // for all of the meshes and give the data to the GPU.
        if (usePerMeshUniforms) {
            // Update uniforms for the "ground" mesh
            perMeshUniformsData[0].r = 1f;
            perMeshUniformsData[0].g = 1f;
            perMeshUniformsData[0].b = 1f;
            perMeshUniformsData[0].a = 0f;

            // Compute the per mesh uniforms for all of the "building" meshes
            int index = 1;

            for (int i = 0; i < SQRT_BUILDING_COUNT; i++) {

                for (int j = 0; j < SQRT_BUILDING_COUNT; j++, index++) {

                    float x, z, radius;

                    x = (float) i / (float) SQRT_BUILDING_COUNT - .5f;
                    z = (float) i / (float) SQRT_BUILDING_COUNT - .5f;
                    radius = (float) Math.sqrt(x * x + z * z);

                    perMeshUniformsData[index].r = (float) Math.sin(10f * radius + t);
                    perMeshUniformsData[index].g = (float) Math.cos(10f * radius + t);
                    perMeshUniformsData[index].b = radius;
                    perMeshUniformsData[index].a = 0f;
                    perMeshUniformsData[index].u = (float) j / (float) SQRT_BUILDING_COUNT;
                    perMeshUniformsData[index].v = (float) i / (float) SQRT_BUILDING_COUNT;
                }
            }

            // Give the uniform data to the GPU
            float[] fb = new float[perMeshUniformsData.length * 6];
            for (int i = 0; i < perMeshUniformsData.length; i++) {
                System.arraycopy(perMeshUniformsData, 0, fb, i * 6, 6);
            }
            FloatBuffer floatBuffer = GLBuffers.newDirectFloatBuffer(fb);
            gl4.glNamedBufferData(perMeshUniforms[0], 6 * GLBuffers.SIZEOF_FLOAT,
                    floatBuffer, GL4.GL_STREAM_DRAW);
        } else {
            // All meshes will use these uniforms
            perMeshUniformsData[0].r = (float) Math.sin(t);
            perMeshUniformsData[0].g = (float) Math.cos(t);
            perMeshUniformsData[0].b = 1f;
            perMeshUniformsData[0].a = 0f;

            // Give the uniform data to the GPU
            float[] fb = perMeshUniformsData[0].toFloatbuffer();
            FloatBuffer floatBuffer = GLBuffers.newDirectFloatBuffer(fb);
            gl4.glNamedBufferSubData(perMeshUniforms[0], 0, 6, floatBuffer);
        }

        if (useBindlessUniforms) {
            // *** INTERESTING ***
            // Get the GPU pointer for the per mesh uniform buffer and make the 
            // buffer resident on the GPU. For bindless uniforms, this GPU pointer 
            // will later be passed to the vertex shader via a vertex attribute. 
            // The vertex shader will then directly use the GPU pointer to access 
            // the uniform data.
            int[] perMeshUniformsDataSize = new int[1];
            gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, perMeshUniforms[0]);
            gl4.glGetBufferParameterui64vNV(GL4.GL_UNIFORM_BUFFER,
                    GL4.GL_BUFFER_GPU_ADDRESS_NV, perMeshUniformsGPUPtr, 0);
            gl4.glGetBufferParameteriv(GL4.GL_UNIFORM_BUFFER, GL4.GL_BUFFER_SIZE,
                    perMeshUniformsDataSize, 0);
            gl4.glMakeBufferResidentNV(GL4.GL_UNIFORM_BUFFER, GL4.GL_READ_ONLY);
            gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0);
        }
    }

    private void initUBO(GL4 gl4, int blockBinding) {

        ubo = new int[1];
        int size = 16 * GLBuffers.SIZEOF_FLOAT;

        gl4.glGenBuffers(1, ubo, 0);
        gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, ubo[0]);
        {
            gl4.glBufferData(GL4.GL_UNIFORM_BUFFER, size * 2, null, GL4.GL_DYNAMIC_DRAW);

            gl4.glBindBufferBase(GL4.GL_UNIFORM_BUFFER, blockBinding, ubo[0]);
        }
        gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0);
    }

    @Override
    public void dispose(GLAutoDrawable glad) {
        System.out.println("dispose");
    }

    /**
     * Performs the actual rendering
     *
     * @param glad
     */
    @Override
    public void display(GLAutoDrawable glad) {

        GL4 gl4 = glad.getGL().getGL4();

        Mat4 modelviewMatrix;

        gl4.glClearColor(.5f, .5f, .5f, 1f);
        gl4.glClear(GL4.GL_COLOR_BUFFER_BIT | GL4.GL_DEPTH_BUFFER_BIT);
        gl4.glEnable(GL4.GL_DEPTH_TEST);

        // Enable the vertex and pixel shader
        shader.bind(gl4);

        if (useBindlessTextures) {
            int samplersLocation = gl4.glGetUniformLocation(shader.getProgramId(), "samplers");
            gl4.glUniformHandleui64vARB(samplersLocation, TEXTURE_FRAME_COUNT,
                    textureHandles, 0);
        }

        int bindlessTexture = gl4.glGetUniformLocation(shader.getProgramId(), "useBindless");
        gl4.glUniform1i(bindlessTexture, useBindlessTextures ? 1 : 0);

        int currentTexture = gl4.glGetUniformLocation(shader.getProgramId(), "currentFrame");
        gl4.glUniform1i(currentTexture, currentFrame);

        // Set up the transformation matices up
        modelviewMatrix = transformer.getModelViewMat();
        transformUniformsData.modelView = modelviewMatrix;
        transformUniformsData.modelViewProjection = projectionMatrix.mult(modelviewMatrix);
        transformUniformsData.useBindlessUniforms = useBindlessUniforms ? 1 : 0;
        gl4.glBindBufferBase(GL4.GL_UNIFORM_BUFFER, 2, transformUniforms[0]);
        gl4.glNamedBufferSubData(transformUniforms[0], 0, TransformUniforms.size(),
                GLBuffers.newDirectFloatBuffer(transformUniformsData.toFloatarray()));

        // If we are going to update the uniforms every frame, do it now
        if (updateUniformsEveryFrame) {
            float deltaTime;
            float dt;

            deltaTime = getFrameDeltaTime();
            if (deltaTime < minimumFrameDeltaTime) {
                minimumFrameDeltaTime = deltaTime;
            }
            dt = Math.min(.00005f / minimumFrameDeltaTime, .01f);
            t += dt * Mesh.drawCallsPerState;

            updatePerMeshUniforms(gl4, t);
        }

        // Set up default per mesh uniforms. These may be changed on a per mesh 
        // basis in the rendering loop below 
        if (useBindlessUniforms) {
            // *** INTERESTING ***
            // Pass a GPU pointer to the vertex shader for the per mesh uniform 
            // data via a vertex attribute
            gl4.glVertexAttribI2i(bindlessPerMeshUniformsPtrAttribLocation,
                    (int) (perMeshUniformsGPUPtr[0] & 0xFFFFFFFF),
                    (int) ((perMeshUniformsGPUPtr[0] >> 32) & 0xFFFFFFFF));
        } else {
            gl4.glBindBufferBase(GL4.GL_UNIFORM_BUFFER, 3, perMeshUniforms[0]);
            FloatBuffer fb = GLBuffers.newDirectFloatBuffer(perMeshUniformsData[0].toFloatbuffer());
            gl4.glNamedBufferSubData(perMeshUniforms[0], 0, 6, fb);
        }

        // If all of the meshes are sharing the same vertex format, we can just 
        // set the vertex format once
        if (!Mesh.setVertexFormatOnEveryDrawCall) {
            Mesh.renderPrep(gl4);
        }

        // Render all of the meshes
        for (int i = 0; i < meshes.length; i++) {

            // If enabled, update the per mesh uniforms for each mesh rendered
            if (usePerMeshUniforms) {

                if (useBindlessUniforms) {

                    long perMeshUniformsGPUPtr_;

                    // *** INTERESTING ***
                    // Compute a GPU pointer for the per mesh uniforms for this mesh
                    perMeshUniformsGPUPtr_ = perMeshUniformsGPUPtr[0]
                            + PerMeshUniforms.size() * i;
                    // Pass a GPU pointer to the vertex shader for the per mesh 
                    // uniform data via a vertex attribute
                    gl4.glVertexAttribI2i(bindlessPerMeshUniformsPtrAttribLocation,
                            (int) (perMeshUniformsGPUPtr_ & 0xFFFFFFFF),
                            (int) ((perMeshUniformsGPUPtr_ >> 32) & 0xFFFFFFFF));
                } else {

                    gl4.glBindBufferBase(GL4.GL_UNIFORM_BUFFER, 3, perMeshUniforms[0]);
                    gl4.glNamedBufferSubData(perMeshUniforms[0], 0, PerMeshUniforms.size(),
                            GLBuffers.newDirectFloatBuffer(perMeshUniformsData[0].toFloatbuffer()));
                }
            }

            // If we're not sharing vertex formats between meshes, we have to 
            // set the vertex format everytime it changes.
            if (Mesh.setVertexFormatOnEveryDrawCall) {
                Mesh.renderPrep(gl4);
            }

            // Now that everything is set up, do the actual rendering
            // The code that selects between rendering with Vertex Array Objects 
            // (VAO) and Vertex Buffer Unified Memory (VBUM) is located in 
            // Mesh::render(). The code that gets the GPU pointer for use with 
            // VBUM rendering is located in Mesh::update()
            meshes[i].render(gl4);
            
            // If we're not sharing vertex formats between meshes, we have to 
            // reset the vertex format to a default state after each mesh
        }

        checkError(gl4);

        frameTime = System.currentTimeMillis();
    }

    private long getFrameDeltaTime() {
        return System.currentTimeMillis() - frameTime;
    }

    private void updateCamera(GL4 gl4) {

        gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, ubo[0]);
        {
            int size = 16 * GLBuffers.SIZEOF_FLOAT;
            int offset = 0;

            FloatBuffer viewMat = GLBuffers.newDirectFloatBuffer(viewPole.calcMatrix().toFloatArray());

            gl4.glBufferSubData(GL4.GL_UNIFORM_BUFFER, offset, size, viewMat);
        }
        gl4.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0);
    }

    private void checkError(GL4 gl4) {

        int error = gl4.glGetError();

        if (error != GL4.GL_NO_ERROR) {
            System.out.println("error " + error);
        }
    }

    @Override
    public void reshape(GLAutoDrawable glad, int x, int y, int width, int height) {
        System.out.println("reshape");

        GL4 gl4 = glad.getGL().getGL4();

//        weightedBlended.reshape(gl4, width, height);
        imageSize = new Vec2i(width, height);

        updateProjection(gl4, width, height);

        gl4.glViewport(0, 0, width, height);

        checkError(gl4);
    }

    private void updateProjection(GL4 gl3, int width, int height) {

        gl3.glBindBuffer(GL4.GL_UNIFORM_BUFFER, ubo[0]);
        {
            float aspect = (float) width / (float) height;
            int size = 16 * GLBuffers.SIZEOF_FLOAT;
            int offset = size;

            Mat4 projMat = Jglm.perspective(60f, aspect, 0.0001f, 10);
            FloatBuffer projFB = GLBuffers.newDirectFloatBuffer(projMat.toFloatArray());

            gl3.glBufferSubData(GL4.GL_UNIFORM_BUFFER, offset, size, projFB);
        }
        gl3.glBindBuffer(GL4.GL_UNIFORM_BUFFER, 0);
    }

    public NewtCanvasAWT getNewtCanvasAWT() {
        return newtCanvasAWT;
    }

    public GLWindow getGlWindow() {
        return glWindow;
    }

    public Animator getAnimator() {
        return animator;
    }
}
