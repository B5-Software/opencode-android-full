// SPDX-License-Identifier: GPL-3.0-or-later
import path from "node:path"
import os from "node:os"
import { access, realpath, stat } from "node:fs/promises"

const slash = (value) => value.replaceAll("\\", "/")
const contains = (root, file) => file === root || file.startsWith(root.endsWith(path.sep) ? root : root + path.sep)

async function projectRoot(directory) {
  for (let current = directory; ; current = path.dirname(current)) {
    for (const marker of [".git", ".hg"]) {
      try { await access(path.join(current, marker)); return current } catch {}
    }
    if (path.dirname(current) === current) return directory
  }
}

/** Match upstream FileAccess resources, additionally checking a symlink's actual target. */
export class FilePermissions {
  constructor(directory, worktree = directory) {
    this.directory = path.resolve(directory)
    this.worktree = path.resolve(worktree)
    this.authorized = new WeakSet()
  }

  async resolve(input) {
    if (typeof input !== "string" || !input || input.includes("\0")) throw new Error("A filePath is required")
    const expanded = input === "~" ? os.homedir() : input.startsWith("~/") ? path.join(os.homedir(), input.slice(2)) : input
    const absolute = path.resolve(this.directory, expanded)
    const file = await realpath(absolute)
    if (!(await stat(file)).isFile()) throw new Error("LSP filePath must be a regular file")
    return { absolute, file }
  }

  async authorize(ctx, context, input) {
    const target = await this.resolve(input)
    const realDirectory = await realpath(this.directory)
    const realWorktree = await realpath(this.worktree).catch(() => realDirectory)
    const resources = []; const external = []; const saves = []
    for (const [file, directory, worktree] of [
      [target.absolute, this.directory, this.worktree], [target.file, realDirectory, realWorktree],
    ]) {
      const internal = contains(directory, file) || (worktree !== path.parse(worktree).root && contains(worktree, file))
      resources.push(slash(internal ? path.relative(directory, file) || "." : file))
      if (!internal) {
        const parent = path.dirname(file)
        external.push(slash(path.join(parent, "*")))
        saves.push(slash(path.join(await projectRoot(parent), "*")))
      }
    }
    const invocation = { sessionID: context.sessionID, agent: context.agent,
      source: { type: "tool", messageID: context.messageID, id: context.id } }
    if (external.length) await ctx.permission.assert({ ...invocation, action: "external_directory",
      resources: [...new Set(external)], save: [...new Set(saves)] })
    await ctx.permission.assert({ ...invocation, action: "read", resources: [...new Set(resources)], save: ["*"] })
    const token = Object.freeze(target)
    this.authorized.add(token)
    await this.check(token)
    return token
  }

  async check(token) {
    if (!token || !this.authorized.has(token)) throw new Error("LSP file authorization is required")
    if (await realpath(token.absolute) !== token.file || await realpath(token.file) !== token.file)
      throw new Error("LSP file target changed after authorization")
    return token.file
  }
}
