// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
	integrations: [
		starlight({
			title: 'MemLayer',
			description: 'Persistent memory for AI agents',
			sidebar: [
				{
					label: 'Getting Started',
					items: [
						{ label: 'Installation', slug: 'getting-started/installation' },
						{ label: 'Connect Your Agent', slug: 'getting-started/setup' },
						{ label: 'Quickstart: MCP', slug: 'getting-started/quickstart-mcp' },
						{ label: 'Quickstart: API', slug: 'getting-started/quickstart-api' },
					],
				},
				{
					label: 'Concepts',
					items: [
						{ label: 'Overview', slug: 'concepts/overview' },
						{ label: 'Semantic Layers', slug: 'concepts/semantic-layers' },
						{ label: 'Temporal Queries', slug: 'concepts/temporal-queries' },
						{ label: 'Knowledge Graph', slug: 'concepts/knowledge-graph' },
					],
				},
				{
					label: 'Operations',
					items: [
						{ label: 'Retain', slug: 'operations/retain' },
						{ label: 'Recall', slug: 'operations/recall' },
						{ label: 'Reflect', slug: 'operations/reflect' },
						{ label: 'Forget', slug: 'operations/forget' },
					],
				},
				{
					label: 'API Reference',
					items: [
						{ label: 'Overview', slug: 'api-reference/overview' },
						{ label: 'Memories', slug: 'api-reference/memories' },
						{ label: 'Relationships', slug: 'api-reference/relationships' },
						],
				},
				{
					label: 'MCP Integration',
					items: [
						{ label: 'Setup', slug: 'mcp/setup' },
						{ label: 'Tools Reference', slug: 'mcp/tools' },
					],
				},
				{
					label: 'Dashboard',
					items: [
						{ label: 'Overview', slug: 'dashboard/overview' },
					],
				},
				],
		}),
	],
});
