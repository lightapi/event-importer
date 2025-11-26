# event-importer
A utiltity to import events to the Kafka portal-event topc

Key Usage Examples for the CLI
When calling the CLI, you pass the mutation rules as a single JSON string (often enclosed in single quotes '...' in the shell):
1. Replace Host ID (Tenant Migration)
You moved from old_host_uuid to new_host_uuid.
code
Bash
java -jar importer.jar -f events.log -r '[{"field": "hostId", "from": "OLD_HOST_UUID", "to": "NEW_HOST_UUID"}]'
2. Replace Host ID and Generate New Aggregate IDs (Full Isolation)
You want to map the old userId to a new userId and generate new eventIds and subject (aggregate ID).
code
Bash
java -jar importer.jar -f events.log \
    -r '[{"field": "hostId", "from": "OLD_HOST_UUID", "to": "NEW_HOST_UUID"}]' \
    -e '[
        {"field": "id", "action": "generateUUID"}, 
        {"field": "subject", "action": "generateUUID"},
        {"field": "originalUserId", "action": "mapAndGenerate", "sourceField": "userId"}
    ]'
(Note: For the user mapping, you would need a custom solution that first reads a mapping table or performs a one-time query to get the originalUserId from a previous step, and then uses the mapping to generate the new ID consistently.)
