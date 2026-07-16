# 📬 Balance Transfer Service – Backend Homework

This is a technical assignment for backend engineer candidates. You are expected to build a RESTful balance-transfer service using **Spring Boot**, integrating **MySQL**, **Redis**, and **RocketMQ**.


---

## 🎯 Objective

Design and implement an API service that simulates a user balance system, allowing atomic transfers of credit between users.

---

## 🔧 Features to Implement

### 1️⃣ Create User with Initial Balance

**Endpoint:** `POST /users`

```json
{
  "userId": "user_001",
  "initialBalance": 1000
}
```

**Expected Behavior:**
- Store the user and initial balance in MySQL
- Ensure `userId` is unique

---

### 2️⃣ Get Balance by User

**Endpoint:** `GET /users/{userId}/balance`

**Expected Behavior:**
- Return the user's current balance
- May use Redis for caching (bonus)

---

### 3️⃣ Transfer Balance Between Users

**Endpoint:** `POST /transfers`

```json
{
  "fromUserId": "user_001",
  "toUserId": "user_002",
  "amount": 150
}
```

**Expected Behavior:**
- Atomically deduct balance from `fromUserId` and credit to `toUserId`
- Prevent negative balances
- Save transfer history to MySQL
- Use database transactions to ensure consistency

---

### 4️⃣ View Transfer History

**Endpoint:** `GET /transfers?userId=user_001`

**Expected Behavior:**
- Return list of transfers made by or to the specified user
- Support pagination and sort by most recent

---

### 5️⃣ Cancel a Transfer 

**Endpoint:** `POST /transfers/{transferId}/cancel`

**Expected Behavior:**
- Allow cancellation of a recent transfer (e.g., within 10 minutes)
- Reverse the transfer if not yet settled
- Invalidate related cache entries if needed

⸻

🧪 Bonus (Optional)
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088  

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvn spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples  

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
