from pathlib import Path
import shutil, os
root=Path(os.environ['TEMP'])/'dragsim-gui-render-review'
src=root/'src'
wt=Path(r'C:\Users\guren\.codex\worktrees\04a3\DragSimQol')
for name in ['GuiWidgets','GuiTheme','GuiPaint','GuiLayout']:
    target=src/'com/dragsim/macro/client/gui'/f'{name}.java'
    target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(wt/'src/client/java/com/dragsim/macro/client/gui'/f'{name}.java',target)
stubs={
'org/lwjgl/glfw/GLFW.java':'''package org.lwjgl.glfw; public class GLFW { public static final int GLFW_KEY_ESCAPE=256, GLFW_KEY_DOWN=264, GLFW_KEY_UP=265, GLFW_KEY_ENTER=257, GLFW_KEY_KP_ENTER=335, GLFW_KEY_SPACE=32; }''',
'net/minecraft/network/chat/Component.java':'''package net.minecraft.network.chat; public class Component { private final String text; public Component(String s){text=s;} public static Component literal(String s){return new Component(s);} public String getString(){return text;} }''',
'net/minecraft/client/Minecraft.java':'''package net.minecraft.client; public class Minecraft {}''',
'com/dragsim/macro/client/MacroConfig.java':'''package com.dragsim.macro.client; public class MacroConfig {public static void save(){}}''',
'com/dragsim/macro/client/gui/UiFont.java':'''package com.dragsim.macro.client.gui; import net.minecraft.network.chat.Component; public class UiFont { public static Component text(String s){return Component.literal(s);} public static final Component ON=text("ON"), OFF=text("OFF"), BIND_HELD=text("Bind Held"), MACRO=text("Macro"), MACRO_ON=ON,MACRO_OFF=OFF,ARROW_DOWN=text("v"); }''',
'net/minecraft/client/gui/Font.java':'''package net.minecraft.client.gui; import net.minecraft.network.chat.Component; public class Font { public final java.awt.Font awt; public Font(java.awt.Font f){awt=f;} public int width(Component s){return width(s.getString());} public int width(String s){return (int)Math.ceil(awt.getStringBounds(s,new java.awt.font.FontRenderContext(null,true,true)).getWidth());} }''',
'net/minecraft/client/gui/GuiGraphicsExtractor.java':'''package net.minecraft.client.gui; import java.awt.*; import net.minecraft.network.chat.Component; public class GuiGraphicsExtractor { public final Graphics2D graphics; public GuiGraphicsExtractor(Graphics2D g){graphics=g;} public void fill(int x1,int y1,int x2,int y2,int c){graphics.setColor(new Color(c,true));graphics.fillRect(x1,y1,x2-x1,y2-y1);} public void text(Font f,Component s,int x,int y,int c,boolean shadow){graphics.setColor(new Color(c,true));graphics.setFont(f.awt);graphics.drawString(s.getString(),x,y+graphics.getFontMetrics().getAscent()-2);} public void enableScissor(int x1,int y1,int x2,int y2){graphics.setClip(x1,y1,x2-x1,y2-y1);} public void disableScissor(){graphics.setClip(null);} }''',
'GuiRenderReview.java':r'''import java.awt.*; import java.awt.image.BufferedImage; import java.nio.file.*; import java.util.*; import javax.imageio.ImageIO; import com.dragsim.macro.client.gui.*; import com.dragsim.macro.client.gui.GuiWidgets.*; import net.minecraft.client.gui.GuiGraphicsExtractor;
public class GuiRenderReview {
 public static void main(String[] args)throws Exception {
  java.awt.Font awt=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,Path.of(args[0]).toFile()).deriveFont(12f);
  net.minecraft.client.gui.Font font=new net.minecraft.client.gui.Font(awt);
  for(int[] size:new int[][]{{320,240},{426,240},{854,480}}){
   int sw=size[0],sh=size[1]; BufferedImage image=new BufferedImage(sw*2,(sh+22)*2,BufferedImage.TYPE_INT_RGB);
   Graphics2D raw=image.createGraphics();raw.scale(2,2);raw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
   var g=new GuiGraphicsExtractor(raw);var l=GuiLayout.calculate(sw,sh);
   g.fill(0,0,sw,sh+22,0xff090a10);GuiPaint.roundedRect(g,l.windowX(),l.windowY(),l.windowW(),l.windowH(),6,GuiTheme.PANEL);
   g.text(font,UiFont.text("DragSim Controls"),l.windowX()+10,l.windowY()+9,GuiTheme.TEXT_PRIMARY,false);
   if(l.windowW()>=440)g.text(font,UiFont.text("v1.5.0"),l.windowX()+112,l.windowY()+10,GuiTheme.TEXT_SECONDARY,false);
   String[] pages={"Mining Bot","Route Walker","Combat","Visuals","Advanced"};
   for(int i=0;i<pages.length;i++){int ry=l.windowY()+33+i*l.navRowH();if(i==0)g.fill(l.windowX(),ry,l.windowX()+l.sidebarW(),ry+l.navRowH(),GuiTheme.SIDEBAR_SELECTED);g.text(font,UiFont.text(pages[i]),l.windowX()+10,ry+7,GuiTheme.TEXT_PRIMARY,false);}
   int x=l.contentX(),y=l.contentY()+5,w=l.contentW();g.enableScissor(x,l.contentY(),x+w,l.contentY()+l.contentH());
   var page=new PageActionSetting("Mining Bot","Mine nearby blocks from one position",()->"STOPPED",()->"Toggle: J",()->{});page.render(g,font,x,y,w,false);y+=page.height();
   g.text(font,UiFont.text("TARGET & AIM"),x+10,y+8,GuiTheme.ACCENT_BRIGHT,false);y+=26;
   var dropdown=new DropdownSetting("Target Block","Automatic aim target",new String[]{"Obsidian","Large Amethyst Bud","Amethyst Block"},()->"Large Amethyst Bud",v->{});dropdown.render(g,font,x,y,w,false);y+=dropdown.height();
   var speed=new SliderSetting("Turn Speed","Combined yaw and pitch speed",30,240,5,()->120,v->{},v->"120 deg/s");speed.render(g,font,x,y,w,false);y+=speed.height();
   g.text(font,UiFont.text("AIM DRIFT"),x+10,y+8,GuiTheme.ACCENT_BRIGHT,false);y+=26;
   var toggle=new ToggleSetting("Aim Drift","Slow movement within the selected block",()->true,v->{});toggle.tickAnimation();toggle.render(g,font,x,y,w,false);y+=toggle.height();
   var amount=new SliderSetting("Drift Amount","Maximum in-block offset",0,0.03,0.001,()->0.018,v->{},v->"0.018 blocks");amount.render(g,font,x,y,w,false);g.disableScissor();
   g.text(font,UiFont.text("CODE LAYOUT PREVIEW / NOT IN GAME"),6,sh+5,0xffa399b4,false);raw.dispose();Path out=Path.of(args[1],"gui-review-mining-"+sw+"x"+sh+".png");ImageIO.write(image,"PNG",out.toFile());System.out.println(out);
  }
 }
}'''
}
for rel,text in stubs.items():
    p=src/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
print(root)