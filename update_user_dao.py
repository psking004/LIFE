with open('./app/src/main/java/com/example/data/local/dao/UserDao.kt', 'r') as f:
    content = f.read()

content = content.replace('suspend fun updateAuthUserId(id: String, authUserId: String)(id: String, passwordHash: String)', 'suspend fun updateAuthUserId(id: String, authUserId: String)')
with open('./app/src/main/java/com/example/data/local/dao/UserDao.kt', 'w') as f:
    f.write(content)
