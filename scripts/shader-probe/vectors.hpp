// 为真实判定源码提供最小 GLSL 向量适配，不使用图形 API。
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdint>
struct vec2 { float x,y; vec2(float v=0):x(v),y(v){} vec2(float a,float b):x(a),y(b){} };
struct vec4 { float x,y,z,w; vec4(float v=0):x(v),y(v),z(v),w(v){} vec4(float a,float b,float c,float d):x(a),y(b),z(c),w(d){} };
struct bvec4 { bool x,y,z,w; };
inline vec2 operator+(vec2 a,vec2 b){return {a.x+b.x,a.y+b.y};}
inline vec2 operator-(vec2 a,vec2 b){return {a.x-b.x,a.y-b.y};}
inline vec2 operator*(vec2 a,float b){return {a.x*b,a.y*b};}
inline vec2 operator*(float a,vec2 b){return b*a;}
inline vec2 operator/(vec2 a,float b){return {a.x/b,a.y/b};}
inline vec2 operator/(vec2 a,vec2 b){return {a.x/b.x,a.y/b.y};}
inline float shader_abs(float a){return std::fabs(a);}
inline vec2 shader_abs(vec2 a){return {std::fabs(a.x),std::fabs(a.y)};}
inline vec2 ceil(vec2 a){return {std::ceil(a.x),std::ceil(a.y)};}
inline vec2 floor(vec2 a){return {std::floor(a.x),std::floor(a.y)};}
inline float min(float a,float b){return std::min(a,b);}
inline float max(float a,float b){return std::max(a,b);}
inline float mod(float x,float y){return x-y*std::floor(x/y);}
inline vec2 min(vec2 a,vec2 b){return {min(a.x,b.x),min(a.y,b.y)};}
inline vec2 max(vec2 a,vec2 b){return {max(a.x,b.x),max(a.y,b.y)};}
inline bvec4 lessThan(vec4 a,vec4 b){return {a.x<b.x,a.y<b.y,a.z<b.z,a.w<b.w};}
inline bool any(bvec4 a){return a.x||a.y||a.z||a.w;}
vec2 rafterGuiPosition,rafterQuad,rafterViewport;
vec2 guiDx,guiDy,quadDx,quadDy;
inline vec2 dFdx(const vec2& a){return &a==&rafterGuiPosition?guiDx:quadDx;}
inline vec2 dFdy(const vec2& a){return &a==&rafterGuiPosition?guiDy:quadDy;}
inline vec2 fwidth(const vec2& a){return shader_abs(dFdx(a))+shader_abs(dFdy(a));}
// 避免 C 库选择整数 abs 重载。
#define abs shader_abs
