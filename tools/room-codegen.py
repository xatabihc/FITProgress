#!/usr/bin/env python3
"""Regenerate Room 2.7.1 Java and schemas using cached official processor and local KAPT."""
from pathlib import Path
import subprocess,re
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'; out=Path('app/build/offline-verification/room').resolve();out.mkdir(parents=True,exist_ok=True)
cp=Path('app/build/offline-verification/classpath.txt').read_text()
processor=[]
for g in cache.iterdir():
 if g.name not in ['androidx.room','org.xerial','com.squareup','com.google.auto','com.google.auto.value','com.google.guava','com.google.code.gson','commons-codec','com.google.devtools.ksp','org.slf4j','org.jetbrains.kotlin','org.jetbrains.kotlinx','org.jetbrains','com.intellij']: continue
 for a in g.iterdir():
  if g.name=='org.jetbrains.kotlin' and not a.name.startswith(('kotlin-stdlib','kotlin-reflect','kotlin-metadata')):continue
  versions=sorted([v for v in a.iterdir() if any(p for p in v.rglob("*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar")))],key=lambda p:tuple(map(int,re.findall(r'\d+',p.name))))
  if not versions:continue
  for p in versions[-1].rglob('*.jar'):
   if not p.name.endswith(('-sources.jar','-javadoc.jar')):processor.append(str(p))
cmd=['/opt/android-studio/plugins/Kotlin/kotlinc/bin/kapt','-language-version','1.9','-jvm-target','17','-Kapt-mode=stubsAndApt','-Kapt-sources='+str(out/'generated'),'-Kapt-classes='+str(out/'classes'),'-Kapt-stubs='+str(out/'stubs'),'-Kapt-processors=androidx.room.RoomProcessor','-Kapt-option:room.schemaLocation='+str(Path('app/schemas').resolve()),'-cp',cp,'-d',str(out/'output'),'app/src/main/java/ru/fitprogress/ai/DatabaseModels.kt','app/src/main/java/ru/fitprogress/ai/SchemaMigrations.kt']
cmd[1:1] = ['-Kapt-classpath='+p for p in processor]
r=subprocess.run(cmd)
print('KAPT exit',r.returncode)
raise SystemExit(r.returncode)
