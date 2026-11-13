#version 130
// Lumen GPU lighting: faces near lights, drawn again over the terrain.
uniform vec3 SecOrigin;   // section origin, relative to the volume's frame

out vec3 vPos;
out vec3 vNrm;
out vec2 vUv;
out vec4 vCol;
out float vDist;

void main() {
    vPos = SecOrigin + gl_Vertex.xyz;
    vNrm = gl_Normal;
    vUv = gl_MultiTexCoord0.xy;
    vCol = gl_Color;
    vDist = length((gl_ModelViewMatrix * gl_Vertex).xyz);
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
