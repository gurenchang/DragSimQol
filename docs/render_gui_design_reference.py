from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(r"C:\Users\guren\Documents\DragSimQol")
FONT = Path(r"C:\Users\guren\.codex\worktrees\04a3\DragSimQol\src\main\resources\assets\dragsimmacro\font\oxanium-semibold.ttf")
S = 2
W, H = 710, 420
img = Image.new("RGB", (W*S, H*S), "#090a0f")
d = ImageDraw.Draw(img)
def box(x,y,w,h,c,r=0,outline=None):
    d.rounded_rectangle((x*S,y*S,(x+w)*S,(y+h)*S),radius=r*S,fill=c,outline=outline,width=S)
def text(x,y,s,size=12,c="#eff0f5"):
    f=ImageFont.truetype(str(FONT),size*S)
    d.text((x*S,y*S),s,font=f,fill=c,anchor="lt")
box(15,15,680,384,"#14151d",8)
box(15,15,680,40,"#191a24",8)
text(33,28,"DragSimQol",17)
text(587,31,"SETTINGS",10,"#9598ad")
text(669,28,"X",13,"#9598ad")
box(15,55,138,344,"#111219",0)
nav=["Mining Bot","Route Walker","Combat","Visuals","Advanced"]
for i,label in enumerate(nav):
    yy=74+i*36
    if i==0:
        box(26,yy-6,116,31,"#282036",4)
        box(26,yy-2,3,23,"#b38bef",1)
    text(38,yy,label,12,"#e7daf8" if i==0 else "#9699ae")
text(37,365,"Key Bindings",11,"#b6aac8")
text(176,75,"Mining Bot",23)
text(176,106,"Mine nearby blocks from one position.",11,"#a3a6b7")
box(549,76,121,31,"#754bb0",4)
text(562,85,"Start & Close",12)
text(176,142,"STOPPED",10,"#b69bcc")
text(258,142,"Toggle: J   /   Hold a suitable pickaxe",10,"#a3a6b7")
box(176,168,494,1,"#30313e")
text(176,188,"Target Block",13)
text(176,209,"Choose what the bot aims at.",10,"#a3a6b7")
box(442,185,228,36,"#1d1e2a",4,outline="#424253")
text(456,196,"Large Amethyst Bud",12)
text(650,198,"v",11,"#b89adf")
text(442,231,"Down-facing, not waterlogged",10,"#a3a6b7")
box(176,255,494,1,"#2d2e3b")
text(176,277,"Turn Speed",13)
text(576,277,"120 deg/s",12,"#c9a8f2")
box(442,306,228,3,"#353044",1)
box(442,306,98,3,"#b38bef",1)
box(535,301,11,13,"#d2b3f7",3)
box(176,330,494,1,"#2d2e3b")
text(176,351,"Aim Drift",13)
text(176,372,"Small movement after aiming",10,"#a3a6b7")
box(626,351,43,20,"#714ca5",10)
box(650,354,14,14,"#edddff",7)
text(176,407,"DESIGN REFERENCE — NOT A MINECRAFT SCREENSHOT",9,"#8c829e")
output=ROOT/"docs"/"gui-design-reference.png"
img.save(output)
print(output)