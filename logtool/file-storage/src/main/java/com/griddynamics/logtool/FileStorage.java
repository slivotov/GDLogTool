package com.griddynamics.logtool;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public class FileStorage implements Storage {
    private static final Logger logger = LoggerFactory.getLogger(FileStorage.class);
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormat.forPattern("yyyy-dd-MMM");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormat.forPattern("HH:mm:ss");
    private static final int DAY_PATTERN_STRING_LENGTH = 11;

    private String logFolder = "";
    private long maxFolderSize;
    private long curFolderSize = 0;
    private Tree fileSystem = new Tree();
    private volatile long lastUpdateTime = System.currentTimeMillis();
    private Map<String, HashSet<String>> subscribers = new HashMap<String, HashSet<String>>();
    private Map<String, HashSet<String>> alerts = new HashMap<String, HashSet<String>>();
    private Set<String> quotaAlertSubscribers = new HashSet<String>();
    private String alertingEmail;
    private String alertingPassword;
    private int pageSize;
    private List<DateTime> dates = new ArrayList<DateTime>();

    private Lock subscribersLock = new ReentrantLock(true);
    private Lock alertsLock = new ReentrantLock(true);
    private Lock quotaAlertsLock = new ReentrantLock(true);

    @Required
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize << 10;
    }

    @Required
    public void setAlertingEmail(String alertingEmail) {
        this.alertingEmail = alertingEmail;
    }

    @Required
    public void setAlertingPassword(String alertingPassword) {
        this.alertingPassword = alertingPassword;
    }

    @Required
    public void setLogFolder(String logFolder) {
        this.logFolder = logFolder;
    }

    @Required
    public void setMaxFolderSize(long length) {
        this.maxFolderSize = length << 20;
    }

    @Override
    public synchronized long getLastUpdateTime() {
        return this.lastUpdateTime;
    }

    @Override
    public void subscribe(String filter, String emailAddress) {
        if (isNotBlank(filter) && isNotBlank(emailAddress)) {
            try {
                subscribersLock.lock();
                if (!subscribers.containsKey(filter)) {
                    addFilter(filter);
                }
                subscribers.get(filter).add(emailAddress);
            } finally {
                subscribersLock.unlock();
            }
        }
    }

    @Override
    public void unsubscribe(String filter, String emailAddress) {
        if (isNotBlank(filter) && isNotBlank(emailAddress)) {
            try {
                subscribersLock.lock();
                subscribers.get(filter).remove(emailAddress);
                if (subscribers.get(filter).size() == 0) {
                    subscribers.remove(filter);
                }
            } finally {
                subscribersLock.unlock();
            }
        }
    }

    @Override
    public void subscribeToQuotaAlert(String emailAddress) {
        if (isNotBlank(emailAddress)) {
            try {
                quotaAlertsLock.lock();
                quotaAlertSubscribers.add(emailAddress);
            } finally {
                quotaAlertsLock.unlock();
            }
        }
    }

    @Override
    public void unsubscribeToQuotaAlert(String emailAddress) {
        if (isNotBlank(emailAddress)) {
            try {
                quotaAlertsLock.lock();
                quotaAlertSubscribers.remove(emailAddress);
            } finally {
                quotaAlertsLock.unlock();
            }
        }
    }

    @Override
    public Map<String, HashSet<String>> getSubscribers() {
        try {
            subscribersLock.lock();
            return getCopy(subscribers);
        } finally {
            subscribersLock.unlock();
        }
    }

    @Override
    public void removeFilter(String filter) {
        if (isNotBlank(filter)) {
            try {
                alertsLock.lock();
                subscribersLock.lock();
                subscribers.remove(filter);
                alerts.remove(filter);
            } finally {
                alertsLock.unlock();
                subscribersLock.unlock();
            }
        }
    }

    @Override
    public Map<String, HashSet<String>> getAlerts() {
        try {
            alertsLock.lock();
            return getCopy(alerts);
        } finally {
            alertsLock.unlock();
        }
    }

    @Override
    public void removeAlert(String filter, String message) {
        if (isNotBlank(filter) && isNotBlank(message)) {
            try {
                alertsLock.lock();
                if (alerts.containsKey(filter)) {
                    alerts.get(filter).remove(message);
                }
            } finally {
                alertsLock.unlock();
            }
        }
    }

    @Override
    public Map<String, Map<Integer, List<Integer>>> doSearch(String[] path, String request) throws IOException {
        String[] clearPath = removeNullAndEmptyPathSegments(path);
        String logPath = buildPath(clearPath);
        Searcher searcher = new Searcher(request, pageSize);
        return searcher.doSearch(logPath);
    }

    @Override
    public synchronized void addMessage(String[] path, String timestamp, String message) {
        if (needToWipe()) {
            //sendNotification("Storage quota reached.", quotaAlertSubscribers);
            wipe();
        }

        String[] clearPath = removeNullAndEmptyPathSegments(path);

        String logPath = buildPath(clearPath);
        File dir = new File(logPath);
        if (dir.mkdirs()) {
            lastUpdateTime = System.currentTimeMillis();
        }
        String fileName = constructFileName(timestamp);
        String fullFileName = addToPath(logPath, fileName);
        FileWriter fileWriter = null;
        File log = new File(fullFileName);
        long size = log.length();
        String messageWithTime = createMessage(message, timestamp);
        try {
            fileWriter = new FileWriter(fullFileName, true);
            fileWriter.append(messageWithTime);
            fileWriter.append("\n");
        } catch (IOException ex) {
            logger.error("Tried to append message to: " + fullFileName, ex);
            return;
        } finally {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException ex) {
                logger.error("Tried to close file: " + fullFileName, ex);
                return;
            }
        }

        addDate(fileName);

        addToFileSystem(fileSystem, clearPath);

        curFolderSize += (log.length() - size);

        checkForAlerts(message, fullFileName, timestamp);
    }

    @Override
    public synchronized List<String> getLog(String[] path, String logName) {
        String[] clearPath = removeNullAndEmptyPathSegments(path);
        String logPath = addToPath(buildPath(clearPath), logName);
        try {
            BufferedReader reader = new BufferedReader(new FileReader(logPath));
            List<String> log = new ArrayList<String>();
            String line = reader.readLine();
            while (line != null) {
                log.add(line);
                line = reader.readLine();
            }
            return log;
        } catch (IOException ex) {
            logger.error("Tried to read log file: " + logPath, ex);
            return new ArrayList<String>();
        }
    }

    @Override
    public synchronized void deleteLog(String[] path, String name) {
        if (isBlank(name)) {
            return;
        }
        String[] clearPath = removeNullAndEmptyPathSegments(path);
        String logPath = buildPath(clearPath);
        String logAbsolutePath = addToPath(logPath, name);
        File log = new File(logAbsolutePath);
        long logSize = log.length();
        curFolderSize -= logSize;
        if (!log.delete()) {
            curFolderSize += logSize;
            logger.error("Couldn't delete log file: " + logAbsolutePath);
        }
        File dir = new File(logPath);
        if (dir.list().length == 0) {
            deleteDirectory(clearPath);
        }

        lastUpdateTime = System.currentTimeMillis();
    }

    @Override
    public synchronized void deleteDirectory(String ... path) {
        String[] clearPath = removeNullAndEmptyPathSegments(path);
        if (clearPath.length == 0) {
            return;
        }
        String logPath = buildPath(clearPath);
        long logDirSize = measureSize(logPath);

        File log = new File(logPath);
        if (!deleteDirectory(log)) {
            logger.error("Couldn't delete directory: " + logPath);
        } else {
            curFolderSize -= logDirSize;
            Tree node = fileSystem;
            for (int i = 0; i < clearPath.length - 1; i++) {
                node = node.getChildren().get(clearPath[i]);
            }
            node.getChildren().remove(clearPath[clearPath.length - 1]);
        }

        String[] upPath = getUpPath(clearPath);
        logPath = buildPath(upPath);
        log = new File(logPath);
        if (log.list().length == 0) {
            deleteDirectory(upPath);
        }
    }

    @Override
    public synchronized Tree getTree(int height, String ... path) {
        if (height == -1) {
            return getTree(-1, fileSystem);
        } else if (height == 0) {
            String[] clearPath = removeNullAndEmptyPathSegments(path);
            Tree node = new Tree();
            File folder = new File(buildPath(clearPath));
            String[] logs = folder.list();
            for (String log : logs) {
                node.getChildren().put(log, null);
            }
            return node;
        } else {
            String[] clearPath = removeNullAndEmptyPathSegments(path);
            Tree node = fileSystem;
            for (int i = 0; i < clearPath.length; i++) {
                if (node.getChildren().containsKey(clearPath[i])) {
                    node = node.getChildren().get(clearPath[i]);
                } else {
                    logger.error("Couldn't found path: " + buildPath(clearPath));
                    return new Tree();
                }
            }
            return getTree(height, node);
        }
    }

    private Tree getTree(int height, Tree curNode) {
        if (curNode == null) return null;
        Tree node = new Tree(curNode);
        for (String key : curNode.getChildren().keySet()) {
            if (height > 0 || height < 0) {
                node.getChildren().put(key, getTree(height - 1, curNode.getChildren().get(key)));
            } else {
                node.getChildren().put(key, null);
            }
        }
        return node;
    }

    private void addToFileSystem(Tree curNode, String ... path) {
        if (path.length == 0) {
            return;
        } else if (path.length == 1) {
            curNode.getChildren().put(path[0], null);
            return;
        }
        if (curNode.getChildren().containsKey(path[0])) {
            addToFileSystem(curNode.getChildren().get(path[0]), getSubPath(path));
        } else {
            Tree node = new Tree();
            curNode.getChildren().put(path[0], node);
            addToFileSystem(node, getSubPath(path));
        }

    }


    private void createTreeFromDisk() {
        fileSystem = createTreeFromDisk(logFolder);
    }

    private Tree createTreeFromDisk(String path) {
        File file = new File(path);
        File[] dirs = file.listFiles();
        if (dirs == null) {
            return new Tree();
        }
        boolean hasOnlyFiles = true;
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i].isDirectory()) {
                hasOnlyFiles = false;
            } else {
                addDate(dirs[i].getName());
            }
        }
        if (hasOnlyFiles) {
            return null;
        } else {
            Tree node = new Tree();
            for (File dir : dirs) {
                if (dir.isDirectory()) {
                    node.getChildren().put(dir.getName(), createTreeFromDisk(addToPath(path, dir.getName())));
                }
            }
            return node;
        }
    }

    private String[] getUpPath(String ... path) {
        return Arrays.copyOf(path, path.length - 1);
    }

    private String[] getSubPath(String ... path) {
        return Arrays.copyOfRange(path, 1, path.length);
    }

    private boolean needToWipe() {
        return curFolderSize > maxFolderSize;
    }

    private boolean needToWipeRec() {
        return curFolderSize > maxFolderSize * 0.9;
    }

    private void wipe() {
        if (!wipe(new String[0])) {
            dates.remove(0);
            wipe();
        }
    }

    private boolean wipe(String[] path) {
        String curPath = buildPath(path);
        String fileName = constructFileName(dates.get(0).toString());
        File log = new File(addToPath(curPath, fileName));
        File defaultLog = new File(addToPath(curPath, "default.log"));
        if (defaultLog.exists()) {
            deleteLog(path, "default.log");
        }
        if (needToWipeRec() && log.exists()) {
           deleteLog(path, fileName);
        }
        if (needToWipeRec()) {
            File folder = new File(curPath);
            File[] dirs = folder.listFiles();
            if (dirs == null) {
                return false;
            }
            for (File dir : dirs) {
                if (dir.isDirectory()) {
                    String[] newPath = new String[path.length + 1];
                    for (int i = 0; i < path.length; i++) {
                        newPath[i] = path[i];
                    }
                    newPath[path.length] = dir.getName();
                    if (wipe(newPath)) {
                        return true;
                    }
                }
            }
            return false;
        } else {
            return true;
        }
    }

    private long measureSize(String path) {
        File dir = new File(path);
        if (dir.isDirectory()) {
            long res = 0;
            String[] files = dir.list();
            for (String file : files) {
                res += measureSize(addToPath(path,file));
            }
            return res;
        } else {
            return dir.length();
        }
    }

    private boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (String child : children) {
                if (!deleteDirectory(new File(addToPath(dir.getAbsolutePath(), child)))) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    private String buildPath(String ... path) {
        StringBuffer result = new StringBuffer(logFolder);
        for (int i = 0; i < path.length; i++) {
            result.append(File.separator).append(path[i]);
        }
        return result.toString();
    }

    private String addToPath(String path, String subPath) {
        return new StringBuffer(path).append(File.separator).append(subPath).toString();
    }

    private String constructFileName(String timestamp) {
        try {
            DateTime dateTime = new DateTime(timestamp);
            return new StringBuffer(DAY_FORMATTER.withLocale(Locale.ENGLISH).print(dateTime)).append(".log").toString();
        } catch (Exception ex) {
            logger.error("Couldn't parse date format: " + timestamp, ex);
            return "default.log";
        }
    }

    private String createMessage(String message, String timestamp) {
        try {
            DateTime dateTime = new DateTime(timestamp);
            return new StringBuffer(TIME_FORMATTER.print(dateTime)).append(" ").append(message).toString();
        } catch (Exception ex) {
            logger.error("Couldn't parse date format: " + timestamp, ex);
            return "**:**:** " + message;
        }
    }

    private String[] removeNullAndEmptyPathSegments(String[] path) {
        if (path == null) {
            return new String[0];
        }
        List<String> pathList = new ArrayList<String>();
        for (String pathSegment : path) {
            if (isNotBlank(pathSegment)) {
                pathList.add(pathSegment);
            }
        }
        return pathList.toArray(new String[pathList.size()]);
    }

    private void checkForAlerts(String message, String path, String timestamp) {
        Set<String> filters = new HashSet<String>();
        try {
            subscribersLock.lock();
            filters = new HashSet<String>(subscribers.keySet());
        } finally {
            subscribersLock.unlock();
        }
        for (String filter : filters) {
            if (Pattern.matches(filter, message)) {
                String messageWithTime = createMessage(message, timestamp);
                //sendNotification(filter, messageWithTime, path);
                addAlert(filter, messageWithTime);
            }
        }
    }

    private void sendNotification(String filter, String message, String path) {
        StringBuffer sb = new StringBuffer();
        sb.append("Alert!\n");
        sb.append("Application specification: ").append(path).append("\n");
        sb.append("Filter: ").append(filter).append("\n");
        sb.append("Message: ").append(message).append("\n");

        sendNotification(sb.toString(), subscribers.get(filter));
    }

    private void sendNotification(String message, Set<String> subscribers) {
        Email email = new SimpleEmail();
        email.setHostName("smtp.gmail.com");
        email.setSmtpPort(587);
        email.setAuthentication(alertingEmail, alertingPassword);
        email.setTLS(true);
        try {
            email.setFrom(alertingEmail, "GDLogTool Alerting System");
        } catch (EmailException ex) {
            StringBuffer sb = new StringBuffer();
            sb.append("Cannot resolve email address (");
            sb.append(alertingEmail);
            sb.append(") from which need to send alerts.");
            logger.error(sb.toString(), ex);
            return;
        }
        email.setSubject("GDLogTool alert");
        try {
            email.setMsg(message);
        } catch (EmailException ex) {
            logger.error(ex.getMessage(), ex);
            return;
        }
        for (String subscriber : subscribers) {
            try {
                email.addTo(subscriber);
            } catch (EmailException ex) {
                StringBuffer sb = new StringBuffer();
                sb.append("Cannot resolve email address (");
                sb.append(subscriber);
                sb.append(") to which need to send alerts.");
                logger.error(sb.toString(), ex);
            }
        }
        try {
            email.send();
        } catch (EmailException ex) {
            logger.error("Email sending failed.", ex);
        }
    }

    private void addFilter(String filter) {
        subscribers.put(filter, new HashSet<String>());
    }

    private void addAlert(String filter, String message) {
        try {
            alertsLock.lock();
            if (!alerts.containsKey(filter)) {
                alerts.put(filter, new HashSet<String>());
            }
            alerts.get(filter).add(message);
        } finally {
            alertsLock.unlock();
        }
    }

    private Map<String, HashSet<String>> getCopy(Map<String, HashSet<String>> obj) {
        Map<String, HashSet<String>> copy = new HashMap<String, HashSet<String>>();
        for (String key : obj.keySet()) {
            copy.put(key, new HashSet<String>());
            copy.get(key).addAll(obj.get(key));
        }
        return copy;
    }

    private boolean hasNullsOrEmptyStrings(String[] collection) {
        if (collection == null) {
            return true;
        } else {
            for (String str : collection) {
                if (isBlank(str)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void addDate(String fileName) {
        if (fileName.equals("default.log")) {
            return;
        }
        DateTime date = DAY_FORMATTER.parseDateTime(fileName.substring(0, DAY_PATTERN_STRING_LENGTH));
        if (dates.isEmpty()) {
            dates.add(date);
        } else {
            int index = 0;
            while (index != dates.size() && dates.get(index).isBefore(date)) {
                index++;
            }
            if (index == dates.size() || dates.get(index).isAfter(date)) {
                dates.add(index, date);
            }
        }
    }
}
