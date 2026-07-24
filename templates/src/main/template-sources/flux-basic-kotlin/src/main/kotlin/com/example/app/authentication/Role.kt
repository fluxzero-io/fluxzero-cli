package com.example.app.authentication

enum class Role(vararg val assumedRoles: Role) {
    MANAGER,
    ADMIN(MANAGER),
    OWNER(ADMIN);

    fun matches(userRole: Role?): Boolean {
        if (userRole == null) {
            return false
        }
        if (this == userRole) {
            return true
        }
        for (assumedRole in userRole.assumedRoles) {
            if (matches(assumedRole)) {
                return true
            }
        }
        return false
    }
}
