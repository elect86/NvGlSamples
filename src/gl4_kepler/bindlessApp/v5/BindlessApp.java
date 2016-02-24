/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gl4_kepler.bindlessApp.v5;

import com.jogamp.newt.event.KeyEvent;
import static com.jogamp.opengl.GL.GL_DYNAMIC_DRAW;
import static com.jogamp.opengl.GL2ES3.GL_UNIFORM_BUFFER;
import static com.jogamp.opengl.GL2GL3.*;
import static com.jogamp.opengl.GL3ES3.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static com.jogamp.opengl.GL3ES3.GL_TIMEOUT_EXPIRED;
import static com.jogamp.opengl.GL3ES3.GL_WAIT_FAILED;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.GLBuffers;
import glm.glm;
import glm.mat._4.Mat4;
import glm.vec._2.Vec2;
import glm.vec._3.Vec3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jgli.Gl;
import jgli.Load;
import nvAppBase.BufferUtils;
import nvAppBase.NvSampleApp;
import nvAppBase.ProgramEntry;

/**
 *
 * @author GBarbieri
 */
public class BindlessApp extends NvSampleApp {

    private final int SQRT_BUILDING_COUNT = 200;
    private final int TEXTURE_FRAME_COUNT = 180;
    private final float ANIMATION_DURATION = 5f;

    // Simple collection of meshes to render
    private Mesh[] meshes;

    // Shader stuff
    private NvGLSLProgram shader;

    // uniform buffer object (UBO) for tranform data
    private Mat4 projectionMat;
    private ByteBuffer transformPointer = GLBuffers.newDirectByteBuffer(Mat4.SIZE);
    private ByteBuffer perMeshPointer = GLBuffers.newDirectByteBuffer(PerMesh.SIZE);
    private IntBuffer bufferName = GLBuffers.newDirectIntBuffer(Buffer.MAX);
    private IntBuffer perMeshBufferName;
    private IntBuffer vertexArrayName = GLBuffers.newDirectIntBuffer(1);

    // uniform buffer object (UBO) for mesh param data
    private PerMesh[] perMesh;

    //bindless texture handle
    private IntBuffer textureName;
    private int currentFrame = 0;
    private float currentTime = 0.0f;

    // UI stuff
    private boolean updateUniformsEveryFrame = true;
    private boolean usePerMeshUniforms = true;
    private boolean renderTextures = false;

    // Timing related stuff
    private float t = 0.0f;
    private float minimumFrameDeltaTime = 1e6f;

    private FloatBuffer clearColor = GLBuffers.newDirectFloatBuffer(new float[]{0.5f, 0.5f, 0.5f, 1.0f});
    private FloatBuffer clearDepth = GLBuffers.newDirectFloatBuffer(new float[]{1.0f});

    private long[] fences;

    private class Buffer {

        public static final int TRANSFORM = 0;
        public static final int CONSTANT = 1;
        public static final int MAX = 3;
    }

    public BindlessApp(int width, int height) {
        super("BindlessApp");

        transformer.setTranslationVec(new Vec3(0.0f, 0.0f, -4.0f));
    }

    @Override
    public void initRendering(GL4 gl4) {

        // Create our pixel and vertex shader
        shader = NvGLSLProgram.createFromFiles(gl4, "src/gl4_kepler/bindlessApp/v5/shaders", "v5");
        // Set the initial view
        transformer.setRotationVec(new Vec3((float) Math.toRadians(30.0f), (float) Math.toRadians(30.0f), 0.0f));

        // Create the meshes
        meshes = new Mesh[1 + SQRT_BUILDING_COUNT * SQRT_BUILDING_COUNT];
        perMesh = new PerMesh[meshes.length];
        for (int i = 0; i < perMesh.length; i++) {
            perMesh[i] = new PerMesh();
        }

        // Create a mesh for the ground
        meshes[0] = createGround(gl4, new Vec3(0.f, -.001f, 0.f), new Vec3(5.0f, 0.0f, 5.0f));

        // Create "building" meshes
        int meshIndex = 0;
        for (int i = 0; i < SQRT_BUILDING_COUNT; i++) {

            for (int k = 0; k < SQRT_BUILDING_COUNT; k++) {

                float x, y, z;
                float size;

                x = (float) i / SQRT_BUILDING_COUNT - 0.5f;
                y = 0.0f;
                z = (float) k / SQRT_BUILDING_COUNT - 0.5f;
                size = .025f * (100.0f / SQRT_BUILDING_COUNT);

                meshes[meshIndex + 1] = createBuilding(gl4, new Vec3(5.0f * x, y, 5.0f * z),
                        new Vec3(size, (float) (0.2f + .1f * Math.sin(5.0f * i * k)), size),
                        new Vec2((float) k / SQRT_BUILDING_COUNT, (float) i / SQRT_BUILDING_COUNT));

                meshIndex++;
            }
        }

        if (renderTextures) {
            // Initialize Textures
            initTextures(gl4);
        }

        initBuffers(gl4);

        // Initialize the per mesh Uniforms
        updatePerMeshUniforms(gl4, 0.0f);

        if (Mesh.useVertexArray && !Mesh.setVertexFormatOnEveryDrawCall) {
            gl4.glGenVertexArrays(1, vertexArrayName);
        }

        gl4.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, 0, null, false);
        gl4.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DEBUG_SEVERITY_HIGH, 0, null, true);
        gl4.glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DEBUG_SEVERITY_MEDIUM, 0, null, true);
        
        checkError(gl4, "BindlessApp.initRendering()");
    }

    private void initBuffers(GL4 gl4) {

        gl4.glGenBuffers(Buffer.MAX, bufferName);

        IntBuffer uniformBufferOffset = GLBuffers.newDirectIntBuffer(1);
        gl4.glGetIntegerv(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, uniformBufferOffset);

        gl4.glBindBuffer(GL_UNIFORM_BUFFER, bufferName.get(Buffer.TRANSFORM));
        {
            int uniformBlockSize = Math.max(Mat4.SIZE, uniformBufferOffset.get(0));
            gl4.glBufferData(GL_UNIFORM_BUFFER, uniformBlockSize, null, GL_DYNAMIC_DRAW);
        }
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);

        gl4.glBindBuffer(GL_UNIFORM_BUFFER, bufferName.get(Buffer.CONSTANT));
        {
            int uniformBlockSize = Math.max(Integer.BYTES, uniformBufferOffset.get(0));
            ByteBuffer constantBuffer = GLBuffers.newDirectByteBuffer(Integer.BYTES);
            constantBuffer.asIntBuffer().put(renderTextures ? 1 : 0);
            gl4.glBufferData(GL_UNIFORM_BUFFER, uniformBlockSize, constantBuffer, GL_STATIC_DRAW);
        }
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);

        int uniformBlockSize = Math.max(PerMesh.SIZE, uniformBufferOffset.get(0));
        perMeshBufferName = GLBuffers.newDirectIntBuffer(meshes.length);
        fences = new long[meshes.length];
        gl4.glGenBuffers(meshes.length, perMeshBufferName);
        for (int i = 0; i < meshes.length; i++) {
            gl4.glBindBuffer(GL_UNIFORM_BUFFER, perMeshBufferName.get(i));
            {
                gl4.glBufferData(GL_UNIFORM_BUFFER, uniformBlockSize, null, GL_DYNAMIC_DRAW);
            }
            gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);
        }

        BufferUtils.destroyDirectBuffer(uniformBufferOffset);
    }

    /**
     * Computes per mesh uniforms based on t.
     */
    private void updatePerMeshUniforms(GL4 gl4, float t) {

        // If we're using per mesh uniforms, compute the values for the uniforms for all of the meshes and
        // give the data to the GPU.
        if (usePerMeshUniforms) {

            // Update uniforms for the "ground" mesh
            perMesh[0].r = 1.0f;
            perMesh[0].g = 1.0f;
            perMesh[0].b = 1.0f;
            perMesh[0].a = 0.0f;

            updateBuffer(gl4, 0);

            // Compute the per mesh uniforms for all of the "building" meshes
            int index = 1;
            for (int i = 0; i < SQRT_BUILDING_COUNT; i++) {
                for (int j = 0; j < SQRT_BUILDING_COUNT; j++, index++) {
                    float x, z, radius;

                    x = (float) i / SQRT_BUILDING_COUNT - 0.5f;
                    z = (float) j / SQRT_BUILDING_COUNT - 0.5f;
                    radius = (float) Math.sqrt((x * x) + (z * z));

                    perMesh[index].r = (float) Math.sin(10.0f * radius + t);
                    perMesh[index].g = (float) Math.cos(10.0f * radius + t);
                    perMesh[index].b = radius;
                    perMesh[index].a = 0.0f;
                    perMesh[index].u = (float) j / SQRT_BUILDING_COUNT;
                    perMesh[index].v = 1 - (float) i / SQRT_BUILDING_COUNT;

                    updateBuffer(gl4, index);
                }
            }
        } else {
            // All meshes will use these uniforms
            perMesh[0].r = (float) Math.sin(t);
            perMesh[0].g = (float) Math.cos(t);
            perMesh[0].b = 1.0f;
            perMesh[0].a = 0.0f;
        }
    }

    private void updateBuffer(GL4 gl4, int i) {
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, perMeshBufferName.get(i));
        {
            perMeshPointer.asFloatBuffer().put(perMesh[i].toFa());
            gl4.glBufferSubData(GL_UNIFORM_BUFFER, 0, PerMesh.SIZE, perMeshPointer);
        }
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);
    }

    /**
     * Create a very simple building mesh.
     */
    private Mesh createBuilding(GL4 gl4, Vec3 pos, Vec3 dim, Vec2 uv) {
        Vertex[] vertices = new Vertex[4 * 6];
        short[] indices = new short[6 * 6];
        float r, g, b;

        dim.x *= 0.5f;
        dim.z *= 0.5f;

        // Generate a simple building model (i.e. a box). All of the "buildings" are in world space
        // +Z face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[0] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[1] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[2] = new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[3] = new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);

        // -Z face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[4] = new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[5] = new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[6] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[7] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);

        // +X face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[8] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[9] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[10] = new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[11] = new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);

        // -X face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[12] = new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[13] = new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[14] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[15] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);

        // +Y face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[16] = new Vertex(-dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[17] = new Vertex(+dim.x + pos.x, dim.y + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[18] = new Vertex(+dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[19] = new Vertex(-dim.x + pos.x, dim.y + pos.y, -dim.z + pos.z, r, g, b, 1.0f);

        // -Y face
        r = randomColor();
        g = randomColor();
        b = randomColor();
        vertices[20] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[21] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[22] = new Vertex(+dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[23] = new Vertex(-dim.x + pos.x, 0.0f + pos.y, +dim.z + pos.z, r, g, b, 1.0f);

        // Create the indices
        for (int i = 0; i < 24; i += 4) {

            indices[i / 4 * 6 + 0] = (short) (0 + i);
            indices[i / 4 * 6 + 1] = (short) (1 + i);
            indices[i / 4 * 6 + 2] = (short) (2 + i);

            indices[i / 4 * 6 + 3] = (short) (0 + i);
            indices[i / 4 * 6 + 4] = (short) (2 + i);
            indices[i / 4 * 6 + 5] = (short) (3 + i);
        }

        Mesh building = new Mesh();
        building.init(gl4, vertices, indices);

        return building;
    }

    /**
     * Generates a random color.
     */
    private float randomColor() {
        return (float) (1 - Math.random());
    }

    /**
     * Create a mesh for the ground.
     */
    private Mesh createGround(GL4 gl4, Vec3 pos, Vec3 dim) {

        Vertex[] vertices = new Vertex[4];
        short[] indices;
        float r, g, b;

        dim.x *= 0.5f;
        dim.z *= 0.5f;

        // +Y face
        r = 0.3f;
        g = 0.3f;
        b = 0.3f;
        vertices[0] = new Vertex(-dim.x + pos.x, pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[1] = new Vertex(+dim.x + pos.x, pos.y, +dim.z + pos.z, r, g, b, 1.0f);
        vertices[2] = new Vertex(+dim.x + pos.x, pos.y, -dim.z + pos.z, r, g, b, 1.0f);
        vertices[3] = new Vertex(-dim.x + pos.x, pos.y, -dim.z + pos.z, r, g, b, 1.0f);

        // Create the indices
        indices = new short[]{
            0, 1, 2,
            0, 2, 3};

        Mesh ground = new Mesh();
        ground.init(gl4, vertices, indices);

        return ground;
    }

    private void initTextures(GL4 gl4) {

        textureName = GLBuffers.newDirectIntBuffer(TEXTURE_FRAME_COUNT);

        gl4.glGenTextures(TEXTURE_FRAME_COUNT, textureName);

        for (int i = 0; i < TEXTURE_FRAME_COUNT; i++) {

            try {
                jgli.Texture texture = Load.load("/gl4_kepler/bindlessApp/textures/NV" + i + ".dds");

                gl4.glActiveTexture(GL_TEXTURE0);
                gl4.glBindTexture(GL_TEXTURE_2D, textureName.get(i));
                gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
                gl4.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, texture.levels() - 1);
                gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MAG_FILTER, GL4.GL_NEAREST);
                gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MIN_FILTER, GL4.GL_NEAREST);
                gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_S, GL4.GL_REPEAT);
                gl4.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_T, GL4.GL_REPEAT);

                jgli.Gl.Format glFormat = Gl.translate(texture.format());

                for (int level = 0; level < texture.levels(); level++) {

                    gl4.glTexImage2D(GL_TEXTURE_2D, level, glFormat.internal.value,
                            texture.dimensions(level)[0], texture.dimensions(level)[1], 0,
                            glFormat.external.value, glFormat.type.value, texture.data(level));
                }
            } catch (IOException ex) {
                Logger.getLogger(BindlessApp.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    @Override
    public void draw(GL4 gl4) {

        gl4.glClearBufferfv(GL_COLOR, 0, clearColor);
        gl4.glClearBufferfv(GL_DEPTH, 0, clearDepth);

        gl4.glEnable(GL_DEPTH_TEST);

        // Enable the vertex and pixel shader
        shader.enable(gl4);

        // Set the transformation matrices up
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, bufferName.get(Buffer.TRANSFORM));
        {
            Mat4 mvMat = transformer.getModelViewMat();
            Mat4 mvpMat = projectionMat.mul_(mvMat);

            transformPointer.asFloatBuffer().put(mvpMat.toFa_());

            gl4.glBufferSubData(GL_UNIFORM_BUFFER, 0, Mat4.SIZE, transformPointer);
        }
        gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);

        gl4.glBindBufferBase(GL_UNIFORM_BUFFER, Semantic.Uniform.TRANSFORM, bufferName.get(Buffer.TRANSFORM));
        gl4.glBindBufferBase(GL_UNIFORM_BUFFER, Semantic.Uniform.CONSTANT, bufferName.get(Buffer.CONSTANT));

        if (renderTextures) {
            gl4.glActiveTexture(GL_TEXTURE0);
            gl4.glBindTexture(GL_TEXTURE_2D, textureName.get(currentFrame));
            gl4.glUniform1i(shader.getUniformLocation(gl4, "texture_"), 0);
        }

        // If we are going to update the uniforms every frame, do it now
        if (updateUniformsEveryFrame) {

            float deltaTime;
            float dt;

            deltaTime = getFrameDeltaTime();

            if (deltaTime < minimumFrameDeltaTime) {
                minimumFrameDeltaTime = deltaTime;
            }

            dt = Math.min(0.00005f / minimumFrameDeltaTime, .01f);
            t += dt * Mesh.drawCallsPerState;

            updatePerMeshUniforms(gl4, t);
        }

        if (!usePerMeshUniforms) {
//            gl4.glBindBuffer(GL_UNIFORM_BUFFER, bufferName.get(Buffer.PER_MESH));
//            {
//                if (mapBuffers) {
//                    perMeshPointer = gl4.glMapBufferRange(
//                            GL_UNIFORM_BUFFER, 0, PerMesh.SIZE,
//                            GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
//                }
//                perMeshPointer.asFloatBuffer().put(perMesh[0].toFa());
//                if (mapBuffers) {
//                    // Make sure the uniform buffer is uploaded
//                    gl4.glUnmapBuffer(GL_UNIFORM_BUFFER);
//                } else {
//                    gl4.glBufferSubData(GL_UNIFORM_BUFFER, 0, PerMesh.SIZE, perMeshPointer);
//                }
//            }
//            gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);
        }

        if (Mesh.useVertexArray && !Mesh.setVertexFormatOnEveryDrawCall) {
            gl4.glBindVertexArray(vertexArrayName.get(0));
        }

        if (!Mesh.setVertexFormatOnEveryDrawCall) {
            Mesh.renderPrep_(gl4);
        }

        // Render all of the meshes
        for (int i = 0; i < meshes.length; i++) {

            // If enabled, update the per mesh uniforms for each mesh rendered
            if (usePerMeshUniforms) {
//                gl4.glBindBuffer(GL_UNIFORM_BUFFER, bufferName.get(Buffer.PER_MESH));
//                {
//                    if (mapBuffers) {
//                        perMeshPointer = gl4.glMapBufferRange(
//                                GL_UNIFORM_BUFFER, 0, PerMesh.SIZE,
//                                GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
//                    }
//                    perMeshPointer.asFloatBuffer().put(perMesh[i].toFa());
//                    if (mapBuffers) {
//                        // Make sure the uniform buffer is uploaded
//                        gl4.glUnmapBuffer(GL_UNIFORM_BUFFER);
//                    } else {
//                        gl4.glBufferSubData(GL_UNIFORM_BUFFER, 0, PerMesh.SIZE, perMeshPointer);
//                    }
//                }
//                gl4.glBindBuffer(GL_UNIFORM_BUFFER, 0);
                if (fences[i] > 0) {
                    int result = gl4.glClientWaitSync(fences[i], 0, 1);
                    if (result == GL_TIMEOUT_EXPIRED || result == GL_WAIT_FAILED) {
                        System.out.println("Something is wrong, you will die in 7 days");
                    }
                    gl4.glDeleteSync(fences[i]);
                }
                gl4.glBindBuffer(GL_UNIFORM_BUFFER, perMeshBufferName.get(i));
                perMeshPointer = gl4.glMapBufferRange(GL_UNIFORM_BUFFER, 
                        0, // offset
                        PerMesh.SIZE, // size
                        GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT); // flags
                gl4.glUnmapBuffer(GL_UNIFORM_BUFFER);
            }

            if (Mesh.setVertexFormatOnEveryDrawCall) {
                meshes[i].renderPrep(gl4);
            } else {
                meshes[i].setVertexBuffers(gl4);
            }

            meshes[i].render(gl4);

            if (Mesh.setVertexFormatOnEveryDrawCall) {
                Mesh.renderFinish(gl4);
            }
            
            fences[i] = gl4.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        if (!Mesh.setVertexFormatOnEveryDrawCall) {
            Mesh.renderFinish(gl4);
        }

        // Disable the vertex and pixel shader
        shader.disable(gl4);

        currentTime += getFrameDeltaTime();
        if (currentTime > ANIMATION_DURATION) {
            currentTime = 0.0f;
        }
        currentFrame = (int) (180.0f * currentTime / ANIMATION_DURATION);
    }

    @Override
    public void reshape(GL4 gl4, int x, int y, int width, int height) {

        gl4.glViewport(x, y, width, height);
        projectionMat = glm.perspective_(45f * 2f * (float) Math.PI / 360f, (float) width / height, .1f, 10f);

        checkError(gl4, "BindlessApp.reshape");
    }

    @Override
    public void shutdownRendering(GL4 gl4) {

        gl4.glDeleteProgram(shader.programName);
        gl4.glDeleteTextures(TEXTURE_FRAME_COUNT, textureName);
        for (Mesh meshe : meshes) {
            meshe.dispose(gl4);
        }
        gl4.glDeleteBuffers(Buffer.MAX, bufferName);
        if (Mesh.useVertexArray && !Mesh.setVertexFormatOnEveryDrawCall) {
            gl4.glDeleteVertexArrays(1, vertexArrayName);
        }

        BufferUtils.destroyDirectBuffer(textureName);
        BufferUtils.destroyDirectBuffer(bufferName);
        BufferUtils.destroyDirectBuffer(clearColor);
        BufferUtils.destroyDirectBuffer(clearDepth);
        BufferUtils.destroyDirectBuffer(perMeshPointer);
        BufferUtils.destroyDirectBuffer(transformPointer);
        BufferUtils.destroyDirectBuffer(vertexArrayName);
    }

    private boolean requireExtension(GL4 gl4, String ext) {
        return requireExtension(gl4, ext, true);
    }

    private boolean requireExtension(GL4 gl4, String ext, boolean exitOnFailure) {
        if (gl4.isExtensionAvailable(ext)) {
            return true;
        }
        if (exitOnFailure) {
            System.err.println("The current system does not appear to support the extension " + ext + ", which is "
                    + "required by the sample. This is likely because the system's GPU or driver does not support the "
                    + "extension. Please see the sample's source code for details");
            quit();
        }
        return false;
    }

    private void quit() {
        ProgramEntry.animator.stop();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // TODO add key list
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            quit();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }
}
