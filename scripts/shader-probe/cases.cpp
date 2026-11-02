// 仅回归源判定函数，不调用游戏或图形 API。
struct Quad {float x,y,w,h;};
uint64_t samples=0,misses=0,falsePositives=0;
vec2 rotate(vec2 v,int phase){for(int i=0;i<phase;++i)v={v.y,-v.x};return v;}
bool classify(Quad q,float x,float y,float vw,float vh,int phase,int scale,vec4 color=vec4(1)){
    rafterGuiPosition={x,y};rafterViewport={vw,vh};
    rafterQuad=rotate({2*(x-q.x)/q.w-1,2*(y-q.y)/q.h-1},phase);
    guiDx={1.f/scale,0};guiDy={0,1.f/scale};
    quadDx=rotate({2.f/(q.w*scale),0},phase);quadDy=rotate({0,2.f/(q.h*scale)},phase);
    return rafterDialogFocus(color);
}
void sample(Quad q,float x,float y,float vw,float vh,int phase,int scale,bool expected,const char* kind,vec4 color=vec4(1)){
    bool actual=classify(q,x,y,vw,vh,phase,scale,color);++samples;
    if(actual==expected)return;
    uint64_t previous=misses+falsePositives;
    if(expected)++misses;else ++falsePositives;
    if(previous<8)std::printf("FAIL %s phase=%d scale=%d viewport=%.0fx%.0f quad=(%.2f,%.2f %.2fx%.2f) pixel=(%.3f,%.3f) expected=%d actual=%d\n",kind,phase,scale,vw,vh,q.x,q.y,q.w,q.h,x,y,expected,actual);
}
void raster(Quad q,float vw,float vh,int phase,int scale,float footer,const char* kind){
    int x0=(int)std::ceil(max(q.x,0.f)*scale-.5f);
    int x1=(int)std::ceil(min(q.x+q.w,vw)*scale-.5f);
    int y0=(int)std::ceil(max(q.y,33.f)*scale-.5f);
    int y1=(int)std::ceil(min(q.y+q.h,vh-footer)*scale-.5f);
    for(int iy=y0;iy<y1;++iy)for(int ix=x0;ix<x1;++ix)
        sample(q,(ix+.5f)/scale,(iy+.5f)/scale,vw,vh,phase,scale,true,kind);
}
int main(){
    for(int i=0;i<RAFTER_FOCUS_LAYOUT_COUNT;++i){
        vec4 g=RAFTER_FOCUS_LAYOUTS[i];
        for(float vw:{683.f,684.f,640.f,631.f,320.f})for(float vh:{720.f,240.f}){
            float available=max(0.f,vh-33-g.z), total=g.y+g.w;
            float top=min(63.f,vh-g.z-min(total,available));
            int travel=(int)max(0.f,total-available);
            for(int rounding=0;rounding<2;++rounding){
                float left=rounding?std::ceil((vw-g.x)/2):std::floor((vw-g.x)/2);
                for(int stage=0;stage<(travel?3:1);++stage){
                    int scroll=stage==0?0:stage==1?travel/2:travel;
                    float y=top-scroll;
                    Quad edges[]={{left,y,g.x,1},{left,y+g.y-1,g.x,1},{left,y+1,1,g.y-2},{left+g.x-1,y+1,1,g.y-2}};
                    for(int phase=0;phase<4;++phase)for(int scale:{1,2,3,4}){
                        for(Quad q:edges)raster(q,vw,vh,phase,scale,g.z,"native-border-with-GPU-scissor");
                        for(int edge=0;edge<4;++edge){
                            Quad half=edges[edge];
                            if(edge<2){half.y+=.5f;half.h=.5f;}
                            else {half.x+=.5f;half.w=.5f;}
                            raster(half,vw,vh,phase,scale,g.z,"fractional-thickness-crop");
                        }
                        if(g.x>vw){
                            raster({0,y,vw,1},vw,vh,phase,scale,g.z,"CPU-screen-cropped-horizontal");
                            raster({0,y+g.y-1,vw,1},vw,vh,phase,scale,g.z,"CPU-screen-cropped-horizontal");
                        }
                        // CPU 裁剪后的侧边片段保留原生 X 范围。
                        for(float side:{left,left+g.x-1}){
                            float start=max(y+1,33.f),end=min(y+g.y-1,vh-g.z);
                            if(end>start){
                                Quad piece={side,start,1,(end-start)*.5f};
                                raster(piece,vw,vh,phase,scale,g.z,"CPU-clipped-side");
                            }
                        }
                    }
                }
            }
        }
    }
    // 预期尺寸独立来自编译清单，不从待测判定式推导。
    for(int i=0;i<EXPECTED_NATIVE_LAYOUT_COUNT;++i){
        vec4 g=EXPECTED_NATIVE_LAYOUTS[i];
        for(float vw:{320.f,631.f,683.f,684.f})for(float vh:{240.f,720.f}){
            float ideal=(vw-g.x+g.z)*.5f;
            for(int parentRound=0;parentRound<3;++parentRound){
                float left=parentRound==0?std::floor(ideal)-1:parentRound==1?std::floor(ideal):std::ceil(ideal);
                for(int rows:{0,1,2,5,30}){
                    if(g.w>.5f && rows!=1)continue;
                    float h=g.w>.5f?g.y:9*rows+8;
                    for(float top:{63.f,100.f,-h*.5f})for(int phase=0;phase<4;++phase)for(int scale:{1,2,3,4}){
                        Quad edges[]={{left,top,g.x,1},{left,top+h-1,g.x,1},{left,top+1,1,h-2},{left+g.x-1,top+1,1,h-2}};
                        for(int edge=0;edge<4;++edge){
                            Quad q=edges[edge];raster(q,vw,vh,phase,scale,5,"native-text-item-focus");
                            if(edge<2){q.y+=.5f;q.h=.5f;}else{q.x+=.5f;q.w=.5f;}
                            raster(q,vw,vh,phase,scale,5,"native-fractional-thickness");
                        }
                        // 原生输入与按钮使用纹理管线，不经过 core/gui。
                        // 同时保留实心回退与 GUI 光标。
                        sample({left,100,g.x,20},left+.5f,100.5f,vw,vh,phase,scale,false,"input-solid-fallback");
                        sample({left,100,1,9},left+.5f,100.5f,vw,vh,phase,scale,false,"input-caret");
                    }
                }
            }
        }
    }
    // 非目标像素必须保留，包括输入和页脚。
    for(int phase=0;phase<4;++phase)for(int scale:{1,2,3,4}){
        sample({26,63,631,20},30,65,683,720,phase,scale,false,"solid-white-panel");
        sample({191,133,300,1},200,133.5f,683,720,phase,scale,false,"input-frame");
        sample({26,5,631,1},30,5.5f,683,720,phase,scale,false,"header-line");
        sample({266,716,150,1},270,716.5f,683,720,phase,scale,false,"footer-line");
        sample({26,63,631,1},30,63.5f,683,720,phase,scale,false,"nonwhite-line",vec4(.8f,.8f,.8f,1));
        sample({30,64,1,100},30.5f,70,683,720,phase,scale,false,"interior-vertical-line");
    }
    std::printf("SOURCE-PREDICATE samples=%llu retained-border-pixels=%llu removed-nontarget-pixels=%llu\n",(unsigned long long)samples,(unsigned long long)misses,(unsigned long long)falsePositives);
    return misses||falsePositives?1:0;
}
