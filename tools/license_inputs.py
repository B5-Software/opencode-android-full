#!/usr/bin/env python3
"""Prepare a separate, pinned metadata checkout without running package scripts."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
from upstream_sources import source_git_command,source_git_environment
from build_opencode import build_environment

ROOT=Path(__file__).resolve().parents[1]

def prepare(source,bun):
    source=source.resolve();source.relative_to((ROOT/'.cache').resolve())
    lock=json.loads((ROOT/'runtime/opencode-dev.lock.json').read_text(encoding='utf-8'))
    if not bun:raise ValueError('The locked Bun runtime is required')
    version=subprocess.check_output([bun,'--version'],text=True).strip()
    if version!=lock['bunVersion']:raise ValueError('Notice inputs require the locked Bun version')
    environment=source_git_environment()
    def git(*args,**kwargs):return subprocess.run(source_git_command(source,*args),env=environment,check=True,**kwargs)
    if not (source/'.git').is_dir():
        if source.exists() and any(source.iterdir()):raise ValueError('Existing non-repository notice inputs preserved')
        source.mkdir(parents=True,exist_ok=True);git('init');git('remote','add','origin',lock['repository'])
        git('fetch','--depth=1','--no-tags','origin',lock['commit']);git('checkout','--detach',lock['commit'])
    head=git('rev-parse','HEAD',capture_output=True,text=True).stdout.strip()
    if head!=lock['commit']:raise ValueError('Existing notice source revision differs; preserved')
    if git('status','--porcelain','--untracked-files=no',capture_output=True,text=True).stdout:raise ValueError('Existing edited notice source preserved')
    epoch=git('show','-s','--format=%ct','HEAD',capture_output=True,text=True).stdout.strip()
    install_environment=build_environment(os.environ,lock,'arm64-v8a',bun,epoch)
    # Metadata acquisition does not need any package install hooks or compilers.
    subprocess.run([bun,'install','--frozen-lockfile','--ignore-scripts','--production','--os=linux','--cpu=arm64','--libc=glibc'],cwd=source,env=install_environment,check=True)
    print('Pinned dependency metadata installed without lifecycle scripts.')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,default=ROOT/'.cache/opencode-notice-inputs');parser.add_argument('--bun',default=shutil.which('bun') or shutil.which('bun.exe'))
    args=parser.parse_args();prepare(args.source,str(Path(args.bun).resolve()) if args.bun else None)
