// Display-only projection. Never use these abbreviated fields to build a permissions request.
function accessFields(settings) {
  const result = {};
  if (!settings) return result;
  if (Object.hasOwn(settings, "activePermissionProfile")) {
    const id = settings.activePermissionProfile?.id;
    result.activePermissionProfile = typeof id === "string" ? { id } : null;
  }
  if (Object.hasOwn(settings, "approvalPolicy")) {
    const policy = settings.approvalPolicy;
    result.approvalPolicy = typeof policy === "string" ? policy : Object.keys(policy || {})[0] || null;
  }
  if (Object.hasOwn(settings, "sandboxPolicy") || Object.hasOwn(settings, "sandbox")) {
    const sandbox = settings.sandboxPolicy ?? settings.sandbox;
    result.sandboxPolicy = sandbox?.type || (typeof sandbox === "string" ? sandbox : null);
  }
  return result;
}

function desktopExecution(state) {
  const settings = state?.latestThreadSettings;
  const result = { ...accessFields(state?.currentPermissions), ...accessFields(settings) };
  if (settings && Object.hasOwn(settings, "environments")) result.environments = settings.environments;
  else if (state && Object.hasOwn(state, "environments")) result.environments = state.environments;
  return result;
}

module.exports = { accessFields, desktopExecution };
