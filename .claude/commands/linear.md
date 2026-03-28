# Ensure Linear Ticket

Associate a Linear ticket with this Conductor workspace before starting implementation.

## Steps

1. Check if `.context/linear-ticket` already exists:
   ```bash
   cat .context/linear-ticket 2>/dev/null
   ```
   If it exists, report the current ticket info and ask the user if they want to change it. If not, stop here.

2. Ask the user: **"What's the Linear ticket for this work?"**

   Possible answers:
   - **Ticket ID** (e.g., `MEM-123`): proceed to step 3
   - **"Create one"**: use `mcp__linear-server__save_issue` to create the ticket in Linear (ask for title, team defaults to "Memlayer"), then proceed
   - **Linear URL** (e.g., `https://linear.app/memlayer/issue/MEM-123/some-title`): extract the ticket ID from the URL

3. Fetch the ticket details using `mcp__linear-server__get_issue` to confirm title and current state.

4. **Ensure the ticket belongs to a project:**
   - List existing projects using `mcp__linear-server__list_projects`
   - If the ticket already has a project, confirm it with the user
   - If not, present the list of active projects and ask the user which one to use
   - If no suitable project exists, propose creating one (ask for name and description) using `mcp__linear-server__save_project`
   - Every ticket must belong to a project — do not skip this step

5. **Update the ticket in Linear** using `mcp__linear-server__save_issue`:
   - **Assign** to the current user (`assignee: "me"`)
   - **Set state** to "In Progress"
   - **Project** — set to the project from step 4
   - **Description** — if empty or missing, write one using this structure:
     ```
     ## Problem
     What is the issue or need? Be specific about the current state.

     ## Impact
     How does this affect users, developers, or the system?

     ## Desired Outcome
     What should be true when this ticket is done?
     ```
     For simple tickets, also add a `## Approach` section with a brief suggested direction (but leave implementation details to the implementing agent).
     Draft the description from the user's context and ask them to confirm before saving.
   - If the ticket has no **priority** set (value 0), ask the user or default to Normal (3)
   - If the user mentions a **project/epic**, set it via the `project` field
   - If the user mentions **related tickets**, set them via `relatedTo`, `blocks`, or `blockedBy`
   - If the user provides a **due date**, set it via `dueDate`
   - If the user provides **labels**, set them via `labels`

6. Write the ticket info to `.context/linear-ticket`:
   ```
   TICKET_ID=<ID>
   TICKET_TITLE=<Title>
   TICKET_URL=<URL from Linear>
   ```

7. Rename the current git branch to include the ticket ID:
   ```bash
   git branch -m alenkis/<ticket-id-lowercase>-<short-description>
   ```
   Derive `<short-description>` from the ticket title: lowercase, spaces to hyphens, max 30 chars total for the branch name (after `alenkis/`).

8. Confirm to the user:
   - Ticket tracked: `<ID> — <Title>`
   - Assigned to: current user
   - Status: In Progress
   - Branch renamed to: `alenkis/<new-branch-name>`
   - Remind: PR will automatically include `Fixes <ID>` in the body
   - Remind: After the PR is merged, update the ticket to "Done" via `mcp__linear-server__save_issue`
