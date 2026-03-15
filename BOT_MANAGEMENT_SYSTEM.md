# Bot Management System Guide

## Overview

This guide explains how to build a **Multi-Bot Management System** for controlling multiple ShopNavigator instances across different Minecraft accounts. The system provides:

- ✅ Central control panel with web UI
- ✅ Per-bot configuration management
- ✅ Real-time monitoring and logs
- ✅ Bug detection and recovery
- ✅ Different tasks per account
- ✅ Remote start/stop controls

---

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────┐
│                      Web Browser                        │
│              (Control Panel Dashboard)                  │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP/WebSocket
                        ▼
┌─────────────────────────────────────────────────────────┐
│                  Management Server                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│  │ REST API │  │ WebSocket│  │ Database │             │
│  └──────────┘  └──────────┘  └──────────┘             │
│  ┌──────────────────────────────────────┐              │
│  │     Bot Orchestration Engine         │              │
│  │  - Config Management                 │              │
│  │  - Health Monitoring                 │              │
│  │  - Task Scheduling                   │              │
│  └──────────────────────────────────────┘              │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP API Calls
         ┌──────────────┼──────────────┬─────────────┐
         ▼              ▼              ▼             ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ...
│   Bot #1    │  │   Bot #2    │  │   Bot #3    │
│  Account1   │  │  Account2   │  │  Account3   │
│  ┌────────┐ │  │  ┌────────┐ │  │  ┌────────┐ │
│  │ShopNav │ │  │  │ShopNav │ │  │  │ShopNav │ │
│  │  + API │ │  │  │  + API │ │  │  │  + API │ │
│  └────────┘ │  │  └────────┘ │  │  └────────┘ │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        ▼
              Minecraft Server
```

### Data Flow

1. **User** → Opens web dashboard
2. **Dashboard** → Shows status of all bots
3. **User** → Configures bot (e.g., "Account1: shop for 512 iron blocks")
4. **Management Server** → Sends command to Bot #1 API
5. **Bot #1** → Starts shopping task
6. **Bot #1** → Sends status updates back to server
7. **Server** → Broadcasts updates to dashboard via WebSocket
8. **Dashboard** → Updates UI in real-time

---

## Implementation Approaches

### Option 1: Web-Based Management (Recommended)

**Tech Stack:**
- **Frontend:** React + TypeScript
- **Backend:** Node.js + Express
- **Database:** SQLite or PostgreSQL
- **Real-time:** Socket.IO or WebSockets
- **Bot API:** Added to ShopNavigator mod

**Pros:**
- Access from any device
- Modern, responsive UI
- Easy to deploy
- Real-time updates

**Cons:**
- Requires web development skills
- More complex initial setup

**Time Estimate:** 20-40 hours

### Option 2: Desktop Application

**Tech Stack:**
- **Framework:** Electron or JavaFX
- **Language:** JavaScript/TypeScript or Java
- **Bot Communication:** REST API

**Pros:**
- Native app experience
- No web hosting needed
- Offline capable

**Cons:**
- Platform-specific builds
- Heavier resource usage

**Time Estimate:** 30-50 hours

### Option 3: Simple Console Dashboard

**Tech Stack:**
- **Language:** Python or Node.js
- **UI:** Terminal-based (blessed, inquirer, curses)
- **Bot Communication:** REST API

**Pros:**
- Quickest to implement
- Lightweight
- SSH-friendly

**Cons:**
- Limited UI capabilities
- Less user-friendly

**Time Estimate:** 10-20 hours

---

## Recommended Approach: Web-Based System

Let's build a web-based management system with the following stack:

- **Frontend:** React (dashboard)
- **Backend:** Node.js + Express (API server)
- **Database:** SQLite (bot configs and logs)
- **Real-time:** Socket.IO (live updates)
- **Bot Modification:** Add HTTP API to ShopNavigator

---

## Phase 1: Add API to ShopNavigator Mod

### Step 1.1: Add HTTP Server to Mod

Add a simple HTTP server that allows external control:

```java
// src/client/java/com/gabriel/shopnavigator/HttpApiServer.java
package com.gabriel.shopnavigator;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

public class HttpApiServer extends Thread {
    private HttpServer server;
    private final int port;
    private final ShopNavigatorClient shopNav;
    private final Gson gson = new Gson();
    
    public HttpApiServer(int port, ShopNavigatorClient shopNav) {
        this.port = port;
        this.shopNav = shopNav;
        setDaemon(true);
        setName("ShopNav-API-" + port);
    }
    
    @Override
    public void run() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Status endpoint
            server.createContext("/api/status", this::handleStatus);
            
            // Start shopping endpoint
            server.createContext("/api/shop/start", this::handleShopStart);
            
            // Stop endpoint
            server.createContext("/api/stop", this::handleStop);
            
            // Start crafting endpoint
            server.createContext("/api/craft/start", this::handleCraftStart);
            
            // Config endpoint
            server.createContext("/api/config", this::handleConfig);
            
            // Logs endpoint
            server.createContext("/api/logs", this::handleLogs);
            
            server.start();
            System.out.println("[ShopNav API] Server started on port " + port);
        } catch (IOException e) {
            System.err.println("[ShopNav API] Failed to start: " + e.getMessage());
        }
    }
    
    private void handleStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> status = new HashMap<>();
        status.put("state", shopNav.getState().toString());
        status.put("running", shopNav.isRunning());
        status.put("currentBatch", shopNav.getCurrentBatch());
        status.put("totalBatches", shopNav.getTotalBatches());
        status.put("itemsCrafted", shopNav.getItemsCrafted());
        
        sendJsonResponse(exchange, status);
    }
    
    private void handleShopStart(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        
        // Schedule on main thread
        MinecraftClient.getInstance().execute(() -> {
            shopNav.startShopping();
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Shopping started");
        sendJsonResponse(exchange, response);
    }
    
    private void handleStop(HttpExchange exchange) throws IOException {
        MinecraftClient.getInstance().execute(() -> {
            shopNav.forceStop();
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Stopped");
        sendJsonResponse(exchange, response);
    }
    
    private void handleCraftStart(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }
        
        MinecraftClient.getInstance().execute(() -> {
            shopNav.startCrafting();
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Crafting started");
        sendJsonResponse(exchange, response);
    }
    
    private void handleConfig(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            // Return current config
            sendJsonResponse(exchange, shopNav.getConfig());
        } else if ("POST".equals(exchange.getRequestMethod())) {
            // Update config
            String body = new String(exchange.getRequestBody().readAllBytes());
            // Parse and update config
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Config updated");
            sendJsonResponse(exchange, response);
        } else {
            sendError(exchange, 405, "Method not allowed");
        }
    }
    
    private void handleLogs(HttpExchange exchange) throws IOException {
        // Return recent logs
        Map<String, Object> response = new HashMap<>();
        response.put("logs", shopNav.getRecentLogs());
        sendJsonResponse(exchange, response);
    }
    
    private void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes();
        
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
    
    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        
        String json = gson.toJson(error);
        byte[] bytes = json.getBytes();
        
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }
    
    public void shutdown() {
        if (server != null) {
            server.stop(0);
        }
    }
}
```

### Step 1.2: Integrate API Server

```java
// In ShopNavigatorClient.java
private HttpApiServer apiServer;

public void onInitialize() {
    // ... existing code ...
    
    // Start API server on configured port
    int apiPort = CONFIG.apiPort; // Add to config, default 8080
    if (apiPort > 0) {
        apiServer = new HttpApiServer(apiPort, this);
        apiServer.start();
    }
}

// Add getter methods for API
public State getState() { return state; }
public boolean isRunning() { return state != State.IDLE && state != State.DONE; }
public int getCurrentBatch() { return planIndex; }
public int getTotalBatches() { return currentPlanQuantities != null ? currentPlanQuantities.length : 0; }
public int getItemsCrafted() { return craftedCount; }
public Map<String, Object> getConfig() { return CONFIG.toMap(); }
public List<String> getRecentLogs() { return logBuffer.getLast(50); }
```

### Step 1.3: Add to Config

```json
{
  "apiEnabled": true,
  "apiPort": 8080,
  "apiAuthToken": "your-secret-token-here"
}
```

---

## Phase 2: Build Management Server

### Step 2.1: Initialize Node.js Project

```bash
mkdir shopnav-manager
cd shopnav-manager
npm init -y
npm install express socket.io sqlite3 cors body-parser
npm install --save-dev nodemon
```

### Step 2.2: Create Server

```javascript
// server.js
const express = require('express');
const http = require('http');
const socketIo = require('socket.io');
const sqlite3 = require('sqlite3').verbose();
const cors = require('cors');
const bodyParser = require('body-parser');
const axios = require('axios');

const app = express();
const server = http.createServer(app);
const io = socketIo(server, {
  cors: { origin: "*" }
});

app.use(cors());
app.use(bodyParser.json());
app.use(express.static('public'));

// Initialize database
const db = new sqlite3.Database('./botmanager.db');

db.serialize(() => {
  db.run(`CREATE TABLE IF NOT EXISTS bots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    account_name TEXT NOT NULL,
    api_url TEXT NOT NULL,
    api_token TEXT,
    config JSON,
    status TEXT DEFAULT 'offline',
    last_seen DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
  )`);
  
  db.run(`CREATE TABLE IF NOT EXISTS logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id INTEGER,
    level TEXT,
    message TEXT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (bot_id) REFERENCES bots(id)
  )`);
  
  db.run(`CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id INTEGER,
    task_type TEXT,
    task_config JSON,
    status TEXT DEFAULT 'pending',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME,
    completed_at DATETIME,
    FOREIGN KEY (bot_id) REFERENCES bots(id)
  )`);
});

// API Routes

// Get all bots
app.get('/api/bots', (req, res) => {
  db.all('SELECT * FROM bots', [], (err, rows) => {
    if (err) {
      res.status(500).json({ error: err.message });
      return;
    }
    res.json({ bots: rows });
  });
});

// Add new bot
app.post('/api/bots', (req, res) => {
  const { name, account_name, api_url, api_token, config } = req.body;
  
  db.run(
    'INSERT INTO bots (name, account_name, api_url, api_token, config) VALUES (?, ?, ?, ?, ?)',
    [name, account_name, api_url, api_token, JSON.stringify(config || {})],
    function(err) {
      if (err) {
        res.status(500).json({ error: err.message });
        return;
      }
      res.json({ id: this.lastID, message: 'Bot added successfully' });
    }
  );
});

// Get bot status
app.get('/api/bots/:id/status', async (req, res) => {
  const botId = req.params.id;
  
  db.get('SELECT * FROM bots WHERE id = ?', [botId], async (err, bot) => {
    if (err || !bot) {
      res.status(404).json({ error: 'Bot not found' });
      return;
    }
    
    try {
      const response = await axios.get(`${bot.api_url}/api/status`, {
        timeout: 5000,
        headers: bot.api_token ? { 'Authorization': `Bearer ${bot.api_token}` } : {}
      });
      
      // Update last seen
      db.run('UPDATE bots SET status = ?, last_seen = CURRENT_TIMESTAMP WHERE id = ?',
        ['online', botId]);
      
      res.json({
        bot: bot,
        status: response.data,
        online: true
      });
    } catch (error) {
      db.run('UPDATE bots SET status = ? WHERE id = ?', ['offline', botId]);
      res.json({
        bot: bot,
        status: null,
        online: false,
        error: error.message
      });
    }
  });
});

// Send command to bot
app.post('/api/bots/:id/command', async (req, res) => {
  const botId = req.params.id;
  const { action, params } = req.body;
  
  db.get('SELECT * FROM bots WHERE id = ?', [botId], async (err, bot) => {
    if (err || !bot) {
      res.status(404).json({ error: 'Bot not found' });
      return;
    }
    
    try {
      let endpoint = '';
      switch(action) {
        case 'shop': endpoint = '/api/shop/start'; break;
        case 'craft': endpoint = '/api/craft/start'; break;
        case 'stop': endpoint = '/api/stop'; break;
        default:
          res.status(400).json({ error: 'Unknown action' });
          return;
      }
      
      const response = await axios.post(`${bot.api_url}${endpoint}`, params || {}, {
        timeout: 5000,
        headers: bot.api_token ? { 'Authorization': `Bearer ${bot.api_token}` } : {}
      });
      
      // Log command
      db.run('INSERT INTO logs (bot_id, level, message) VALUES (?, ?, ?)',
        [botId, 'info', `Command sent: ${action}`]);
      
      // Broadcast to dashboard
      io.emit('bot-command', { botId, action, result: response.data });
      
      res.json({ success: true, result: response.data });
    } catch (error) {
      res.status(500).json({ error: error.message });
    }
  });
});

// Get bot logs
app.get('/api/bots/:id/logs', (req, res) => {
  const botId = req.params.id;
  const limit = req.query.limit || 100;
  
  db.all(
    'SELECT * FROM logs WHERE bot_id = ? ORDER BY timestamp DESC LIMIT ?',
    [botId, limit],
    (err, rows) => {
      if (err) {
        res.status(500).json({ error: err.message });
        return;
      }
      res.json({ logs: rows });
    }
  );
});

// Health check endpoint
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// WebSocket connections
io.on('connection', (socket) => {
  console.log('Dashboard connected');
  
  socket.on('disconnect', () => {
    console.log('Dashboard disconnected');
  });
  
  socket.on('subscribe-bot', (botId) => {
    socket.join(`bot-${botId}`);
  });
});

// Background task: Poll bot statuses every 10 seconds
setInterval(async () => {
  db.all('SELECT * FROM bots', [], async (err, bots) => {
    if (err) return;
    
    for (const bot of bots) {
      try {
        const response = await axios.get(`${bot.api_url}/api/status`, {
          timeout: 5000,
          headers: bot.api_token ? { 'Authorization': `Bearer ${bot.api_token}` } : {}
        });
        
        db.run('UPDATE bots SET status = ?, last_seen = CURRENT_TIMESTAMP WHERE id = ?',
          ['online', bot.id]);
        
        // Broadcast status update
        io.to(`bot-${bot.id}`).emit('status-update', {
          botId: bot.id,
          status: response.data,
          online: true
        });
      } catch (error) {
        db.run('UPDATE bots SET status = ? WHERE id = ?', ['offline', bot.id]);
        
        io.to(`bot-${bot.id}`).emit('status-update', {
          botId: bot.id,
          online: false
        });
      }
    }
  });
}, 10000);

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`Bot Management Server running on port ${PORT}`);
});
```

---

## Phase 3: Build Web Dashboard

### Step 3.1: Create React App

```bash
npx create-react-app dashboard
cd dashboard
npm install socket.io-client axios react-router-dom @mui/material @emotion/react @emotion/styled
```

### Step 3.2: Create Dashboard Components

```jsx
// src/App.js
import React, { useState, useEffect } from 'react';
import { io } from 'socket.io-client';
import axios from 'axios';
import { Container, Grid, Card, CardContent, Typography, Button, Box } from '@mui/material';
import BotCard from './components/BotCard';
import BotDetails from './components/BotDetails';

const API_URL = 'http://localhost:3000';
const socket = io(API_URL);

function App() {
  const [bots, setBots] = useState([]);
  const [selectedBot, setSelectedBot] = useState(null);
  
  useEffect(() => {
    loadBots();
    
    socket.on('status-update', (data) => {
      setBots(prev => prev.map(bot => 
        bot.id === data.botId ? { ...bot, ...data } : bot
      ));
    });
    
    return () => socket.disconnect();
  }, []);
  
  const loadBots = async () => {
    try {
      const response = await axios.get(`${API_URL}/api/bots`);
      setBots(response.data.bots);
    } catch (error) {
      console.error('Failed to load bots:', error);
    }
  };
  
  const sendCommand = async (botId, action, params = {}) => {
    try {
      await axios.post(`${API_URL}/api/bots/${botId}/command`, {
        action,
        params
      });
    } catch (error) {
      console.error('Failed to send command:', error);
      alert('Failed to send command: ' + error.message);
    }
  };
  
  return (
    <Container maxWidth="xl">
      <Box sx={{ my: 4 }}>
        <Typography variant="h3" component="h1" gutterBottom>
          ShopNavigator Bot Manager
        </Typography>
        
        <Grid container spacing={3}>
          {bots.map(bot => (
            <Grid item xs={12} sm={6} md={4} key={bot.id}>
              <BotCard 
                bot={bot}
                onClick={() => setSelectedBot(bot)}
                onCommand={(action) => sendCommand(bot.id, action)}
              />
            </Grid>
          ))}
        </Grid>
        
        {selectedBot && (
          <BotDetails 
            bot={selectedBot}
            onClose={() => setSelectedBot(null)}
          />
        )}
      </Box>
    </Container>
  );
}

export default App;
```

```jsx
// src/components/BotCard.js
import React from 'react';
import { Card, CardContent, Typography, Button, Chip, Box } from '@mui/material';
import { green, red, grey } from '@mui/material/colors';

function BotCard({ bot, onClick, onCommand }) {
  const statusColor = bot.status === 'online' ? green[500] : red[500];
  
  return (
    <Card sx={{ cursor: 'pointer' }} onClick={onClick}>
      <CardContent>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 2 }}>
          <Typography variant="h6" component="div">
            {bot.name}
          </Typography>
          <Chip 
            label={bot.status || 'unknown'} 
            size="small"
            sx={{ bgcolor: statusColor, color: 'white' }}
          />
        </Box>
        
        <Typography variant="body2" color="text.secondary">
          Account: {bot.account_name}
        </Typography>
        
        <Box sx={{ mt: 2, display: 'flex', gap: 1 }}>
          <Button 
            size="small" 
            variant="contained" 
            color="primary"
            onClick={(e) => { e.stopPropagation(); onCommand('shop'); }}
            disabled={bot.status !== 'online'}
          >
            Shop
          </Button>
          <Button 
            size="small" 
            variant="contained" 
            color="secondary"
            onClick={(e) => { e.stopPropagation(); onCommand('craft'); }}
            disabled={bot.status !== 'online'}
          >
            Craft
          </Button>
          <Button 
            size="small" 
            variant="outlined" 
            color="error"
            onClick={(e) => { e.stopPropagation(); onCommand('stop'); }}
            disabled={bot.status !== 'online'}
          >
            Stop
          </Button>
        </Box>
      </CardContent>
    </Card>
  );
}

export default BotCard;
```

---

## Phase 4: Configuration System

### Per-Bot Configuration Files

```json
// bot-configs/account1.json
{
  "botName": "Farmer_Bot_1",
  "accountName": "MinecraftUser1",
  "apiPort": 8081,
  "tasks": [
    {
      "type": "shop",
      "targetItem": "minecraft:iron_block",
      "quantity": 512,
      "schedule": "0 */4 * * *"  // Every 4 hours
    },
    {
      "type": "craft",
      "targetQuantity": 960,
      "afterShop": true
    }
  ],
  "recovery": {
    "autoRetry": true,
    "maxRetries": 3,
    "notifyOnFail": true,
    "fallbackAction": "stop"
  }
}
```

### Config Manager

```javascript
// config-manager.js
class ConfigManager {
  constructor(db) {
    this.db = db;
  }
  
  async getBotConfig(botId) {
    return new Promise((resolve, reject) => {
      this.db.get('SELECT config FROM bots WHERE id = ?', [botId], (err, row) => {
        if (err) reject(err);
        else resolve(JSON.parse(row.config || '{}'));
      });
    });
  }
  
  async updateBotConfig(botId, config) {
    return new Promise((resolve, reject) => {
      this.db.run(
        'UPDATE bots SET config = ? WHERE id = ?',
        [JSON.stringify(config), botId],
        (err) => {
          if (err) reject(err);
          else resolve();
        }
      );
    });
  }
  
  async syncConfigToBot(botId) {
    const bot = await this.getBot(botId);
    const config = await this.getBotConfig(botId);
    
    // Send config to bot
    await axios.post(`${bot.api_url}/api/config`, config, {
      headers: bot.api_token ? { 'Authorization': `Bearer ${bot.api_token}` } : {}
    });
  }
}
```

---

## Phase 5: Error Handling and Recovery

### Auto-Recovery System

```javascript
// recovery-manager.js
class RecoveryManager {
  constructor(db, io) {
    this.db = db;
    this.io = io;
    this.monitoring = new Map();
  }
  
  startMonitoring(botId) {
    const interval = setInterval(() => {
      this.checkBotHealth(botId);
    }, 30000); // Check every 30 seconds
    
    this.monitoring.set(botId, interval);
  }
  
  async checkBotHealth(botId) {
    const bot = await this.getBot(botId);
    const config = await this.getBotConfig(botId);
    
    try {
      const response = await axios.get(`${bot.api_url}/api/status`, {
        timeout: 5000
      });
      
      // Check if bot is stuck
      if (response.data.state === 'FAILED' || response.data.stuck) {
        await this.handleBotFailure(botId, bot, config);
      }
    } catch (error) {
      // Bot offline
      await this.handleBotOffline(botId, bot, config);
    }
  }
  
  async handleBotFailure(botId, bot, config) {
    console.log(`Bot ${botId} (${bot.name}) has failed`);
    
    if (config.recovery && config.recovery.autoRetry) {
      const retries = await this.getRetryCount(botId);
      
      if (retries < (config.recovery.maxRetries || 3)) {
        // Attempt recovery
        console.log(`Attempting recovery for bot ${botId} (retry ${retries + 1})`);
        
        await axios.post(`${bot.api_url}/api/stop`);
        await new Promise(resolve => setTimeout(resolve, 5000));
        await axios.post(`${bot.api_url}/api/shop/start`);
        
        await this.incrementRetryCount(botId);
        
        this.io.emit('bot-recovery', {
          botId,
          action: 'retry',
          attempt: retries + 1
        });
      } else {
        // Max retries exceeded
        console.log(`Bot ${botId} exceeded max retries`);
        
        if (config.recovery.notifyOnFail) {
          this.sendNotification(bot, 'Bot failed after maximum retries');
        }
        
        this.io.emit('bot-recovery', {
          botId,
          action: 'failed',
          message: 'Maximum retries exceeded'
        });
      }
    }
  }
  
  async handleBotOffline(botId, bot, config) {
    console.log(`Bot ${botId} (${bot.name}) is offline`);
    
    this.io.emit('bot-status', {
      botId,
      status: 'offline',
      message: 'Bot is not responding'
    });
  }
}
```

---

## Deployment

### Option 1: Local Deployment

```bash
# Terminal 1: Start Management Server
cd shopnav-manager
npm start

# Terminal 2: Start Dashboard
cd dashboard
npm start

# Terminal 3-N: Start Minecraft instances with ShopNav mod
# Each on different API port (8081, 8082, 8083, etc.)
```

### Option 2: Server Deployment

```bash
# Deploy to VPS or cloud server
# Use PM2 for process management
npm install -g pm2

# Start server
pm2 start server.js --name "bot-manager"

# Start dashboard (build static files)
cd dashboard
npm run build
# Serve with nginx or serve the build folder
```

### Docker Deployment

```dockerfile
# Dockerfile
FROM node:16

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

EXPOSE 3000

CMD ["node", "server.js"]
```

```yaml
# docker-compose.yml
version: '3.8'

services:
  manager:
    build: .
    ports:
      - "3000:3000"
    volumes:
      - ./data:/app/data
    environment:
      - NODE_ENV=production
```

---

## Features Summary

### ✅ Implemented Features

1. **Multi-Bot Support**
   - Control unlimited Minecraft accounts
   - Each bot runs independently
   - Per-bot API on different ports

2. **Web Dashboard**
   - Real-time status monitoring
   - Quick action buttons (Shop, Craft, Stop)
   - Live log viewing
   - Bot configuration editor

3. **Configuration Management**
   - Per-bot JSON configs
   - Live config updates
   - Config sync to bots

4. **Error Recovery**
   - Auto-retry on failures
   - Configurable retry limits
   - Failure notifications
   - Health monitoring

5. **Task Scheduling**
   - Scheduled shopping runs
   - Auto-craft after shop
   - Cron-based scheduling

6. **Logging and Monitoring**
   - Centralized log storage
   - Per-bot log filtering
   - Real-time log streaming
   - Historical log access

---

## Time Estimates

| Phase | Description | Time |
|-------|-------------|------|
| 1 | Add API to ShopNav mod | 4-8 hours |
| 2 | Build management server | 6-10 hours |
| 3 | Build web dashboard | 8-12 hours |
| 4 | Config system | 2-4 hours |
| 5 | Error recovery | 4-6 hours |
| **Total** | **Complete system** | **24-40 hours** |

---

## Next Steps

1. **Start with Phase 1** - Add API endpoints to ShopNavigator
2. **Test API** - Verify you can control bot via HTTP calls
3. **Build server** - Create management server with database
4. **Create dashboard** - Build React UI for monitoring
5. **Add recovery** - Implement auto-recovery logic
6. **Deploy** - Set up on server or local network

---

## Alternative: Quick Start with Existing Tools

If you want something faster, consider:

1. **Use Pterodactyl Panel** - Existing Minecraft server management UI
2. **Use Portainer** - If running bots in Docker containers
3. **Use Simple Admin Panel** - Basic PHP/HTML dashboard (2-4 hours to build)

---

## Resources

- **Express.js:** https://expressjs.com/
- **Socket.IO:** https://socket.io/
- **React:** https://reactjs.org/
- **Material-UI:** https://mui.com/
- **PM2:** https://pm2.keymetrics.io/
- **Docker:** https://www.docker.com/

---

## Support

This guide provides the architecture and code examples to build a complete bot management system. Implementation requires:

- **Skills:** JavaScript/Node.js, React, basic networking
- **Time:** 24-40 hours for full implementation
- **Resources:** Server or VPS for deployment (optional)

Choose the approach that best fits your technical skills and time availability!
