import { appendFileSync } from "node:fs"

export default {
  id: "android.fixture.extension",
  async setup(ctx) {
    const record = (event, values = {}) => appendFileSync(ctx.options.receipt,
      JSON.stringify({ transport: "plugin", event, pid: process.pid, ...values }) + "\n")
    record("setup")
    await ctx.tool.transform((editor) => editor.add({
      name: "fixture_plugin_double",
      description: "Synthetic acceptance plugin doubles an integer",
      input: { type: "object", properties: { value: { type: "integer" } }, required: ["value"], additionalProperties: false },
      options: { codemode: false, permission: "fixture_plugin" },
      async execute({ value }) {
        record("call", { value: value * 2 })
        return { content: `PLUGIN_RESULT_${value * 2}` }
      },
    }))
    await ctx.tool.hook("execute.after", (event) => {
      if (event.tool.includes("fixture_plugin_double")) record("execute.after", { status: event.status })
    })
    await ctx.rpc.register({
      id: "android.fixture.rpc",
      methods: { catalog: { input: { type: "object" }, output: { type: "array", items: { type: "string" } } } },
      events: {},
    }, { catalog: async () => (await ctx.tool.list()).map((tool) => `${tool.id} ${tool.name}`) })
    return () => record("cleanup")
  },
}
