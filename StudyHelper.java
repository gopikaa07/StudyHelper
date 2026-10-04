import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class StudyHelper {
    static final int PORT = 8080;
    static final String DB_URL = "jdbc:mysql://localhost:3306/study_helper?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    
    static final String DB_USER = "root";
    static final String DB_PASSWORD = "NewPassword123!";
    static HttpServer server;
    static final Path UPLOAD_ROOT = Paths.get("uploads");
    static final long MAX_IMAGE_SIZE = 2L * 1024L * 1024L;

    public static void main(String[] args) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            initializeDatabase();
            startWebServer();
            System.out.println("========================================");
            System.out.println(" STUDY HELPER WEBSITE");
            System.out.println(" http://localhost:" + PORT);
            System.out.println("========================================");
        } catch (Exception e) {
            System.out.println("STARTUP ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    static void initializeDatabase() throws SQLException {
        try {
            Files.createDirectories(UPLOAD_ROOT.resolve("proof"));
            Files.createDirectories(UPLOAD_ROOT.resolve("notes"));
        } catch (IOException e) {
            throw new SQLException("Could not create upload folders.", e);
        }
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS tutor_requests (request_id INT AUTO_INCREMENT PRIMARY KEY, student_id VARCHAR(20) NOT NULL, tutor_id VARCHAR(20) NULL, group_name VARCHAR(100), subject_code VARCHAR(20), topic VARCHAR(255), amount DOUBLE, status VARCHAR(40), FOREIGN KEY(student_id) REFERENCES students(student_id))");
            


            s.executeUpdate("CREATE TABLE IF NOT EXISTS group_messages (message_id INT AUTO_INCREMENT PRIMARY KEY, group_id INT, student_id VARCHAR(20), message_text VARCHAR(1000), message_time VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS group_notes (note_id INT AUTO_INCREMENT PRIMARY KEY, group_id INT, student_id VARCHAR(20), file_name VARCHAR(255), file_path VARCHAR(500), file_type VARCHAR(20))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS group_schedules (schedule_id INT AUTO_INCREMENT PRIMARY KEY, group_id INT, topic VARCHAR(255), schedule_date VARCHAR(50), schedule_time VARCHAR(50), location VARCHAR(255))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS reports_data (report_id INT AUTO_INCREMENT PRIMARY KEY, reporter_id VARCHAR(20), reported_id VARCHAR(20), reason VARCHAR(500), status VARCHAR(30))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS notifications (notification_id INT AUTO_INCREMENT PRIMARY KEY, student_id VARCHAR(20), message VARCHAR(1000), notification_time VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS reviews_data (review_id INT AUTO_INCREMENT PRIMARY KEY, student_id VARCHAR(20), target VARCHAR(100), review_text VARCHAR(1000))");
        }
    }

    static void startWebServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", StudyHelper::handleStatic);
        server.createContext("/api/student-login", StudyHelper::studentLogin);
        server.createContext("/api/student-signup", StudyHelper::studentSignup);
        server.createContext("/api/teacher-login", StudyHelper::teacherLogin);
        server.createContext("/api/student-operation", StudyHelper::studentOperation);
        server.createContext("/api/teacher-operation", StudyHelper::teacherOperation);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    static void handleStatic(HttpExchange ex) {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                response(ex, 405, "Method Not Allowed");
                return;
            }

            String path = ex.getRequestURI().getPath();

            // Serve uploaded JPG/JPEG images.
            if (path.startsWith("/uploads/")) {
                Path root = UPLOAD_ROOT.toAbsolutePath().normalize();
                Path file = root.resolve(path.substring("/uploads/".length())).normalize();

                if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
                    response(ex, 404, "Not Found");
                    return;
                }

                String lower = file.getFileName().toString().toLowerCase();
                if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                    response(ex, 403, "Forbidden");
                    return;
                }

                byte[] data = Files.readAllBytes(file);
                ex.getResponseHeaders().set("Content-Type", "image/jpeg");
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(data);
                }
                return;
            }

            if ("/".equals(path)) path = "/index.html";

            if (!path.equals("/index.html") && !path.equals("/style.css")) {
                response(ex, 404, "Not Found");
                return;
            }

            Path file = Paths.get(path.substring(1));
            if (!Files.exists(file)) {
                response(ex, 404, "Missing file: " + file.getFileName());
                return;
            }

            byte[] data = Files.readAllBytes(file);
            ex.getResponseHeaders().set(
                    "Content-Type",
                    path.endsWith(".css")
                            ? "text/css; charset=UTF-8"
                            : "text/html; charset=UTF-8"
            );
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, data.length);

            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }

        } catch (Exception e) {
            response(ex, 500, "Server error: " + e.getMessage());
        }
    }

    static void studentLogin(HttpExchange ex) {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { response(ex,405,"Method Not Allowed"); return; }
        Map<String,String> d = form(ex);
        String id=d.get("studentId"), pw=d.get("password");
        try (Connection c=getConnection(); PreparedStatement p=c.prepareStatement("SELECT student_name,account_status FROM students WHERE student_id=? AND password=?")) {
            p.setString(1,id); p.setString(2,pw); ResultSet r=p.executeQuery();
            if (!r.next()) { response(ex,401,"ERROR|Invalid Student ID or password."); return; }
            if (!"ACTIVE".equalsIgnoreCase(r.getString("account_status"))) { response(ex,403,"ERROR|This account is deactivated."); return; }
            response(ex,200,"SUCCESS|"+r.getString("student_name"));
        } catch(Exception e){ response(ex,500,"ERROR|"+safe(e.getMessage())); }
    }

    static void teacherLogin(HttpExchange ex) {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { response(ex,405,"Method Not Allowed"); return; }
        Map<String,String> d=form(ex);
        try(Connection c=getConnection(); PreparedStatement p=c.prepareStatement("SELECT teacher_name FROM teachers WHERE teacher_id=? AND password=? AND verified=TRUE")){
            p.setString(1,d.get("teacherId")); p.setString(2,d.get("password")); ResultSet r=p.executeQuery();
            if(r.next()) response(ex,200,"SUCCESS|"+r.getString(1)); else response(ex,401,"ERROR|Invalid Teacher ID or password.");
        }catch(Exception e){response(ex,500,"ERROR|"+safe(e.getMessage()));}
    }

    static void studentSignup(HttpExchange ex) {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { response(ex,405,"Method Not Allowed"); return; }
        Map<String,String> d=form(ex);
        String id=trim(d.get("studentId")), name=trim(d.get("studentName")), cls=trim(d.get("className")), dept=trim(d.get("department")), tutor=trim(d.get("tutorId")), pw=d.get("password");
        if(blank(id)||blank(name)||blank(cls)||blank(dept)||blank(tutor)||blank(pw)){response(ex,400,"ERROR|Please fill all fields.");return;}
        try(Connection c=getConnection()){
            try(PreparedStatement q=c.prepareStatement("SELECT 1 FROM students WHERE student_id=?")){q.setString(1,id);if(q.executeQuery().next()){response(ex,409,"ERROR|Student ID already exists.");return;}}
            try(PreparedStatement q=c.prepareStatement("SELECT 1 FROM teachers WHERE teacher_id=? AND verified=TRUE")){q.setString(1,tutor);if(!q.executeQuery().next()){response(ex,400,"ERROR|Invalid college teacher ID.");return;}}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO students(student_id,student_name,class_name,department,class_tutor_id,password,warnings,account_status) VALUES(?,?,?,?,?,?,0,'ACTIVE')")){
                p.setString(1,id);p.setString(2,name);p.setString(3,cls);p.setString(4,dept);p.setString(5,tutor);p.setString(6,pw);p.executeUpdate();
            }
            response(ex,200,"SUCCESS|Account created successfully.");
        }catch(Exception e){response(ex,500,"ERROR|"+safe(e.getMessage()));}
    }

    static void studentOperation(HttpExchange ex) {
        try {
            boolean multipart =
                    "POST".equalsIgnoreCase(ex.getRequestMethod())
                    && isMultipart(ex);

            Map<String,String> d;
            MultipartData upload = null;

            if (multipart) {
                upload = parseMultipart(ex);
                d = upload.fields;
            } else {
                d = "GET".equalsIgnoreCase(ex.getRequestMethod()) ? query(ex) : form(ex);
            }

            String id = d.get("studentId");
            String action = d.get("action");

            if (blank(id) || blank(action)) {
                response(ex, 400, "ERROR|Student ID and operation are required.");
                return;
            }

            try (Connection c = getConnection()) {
                if (!studentExists(c, id)) {
                    response(ex, 403, "ERROR|Invalid student account.");
                    return;
                }

                if (multipart) {
                    if ("enterMark".equals(action)) {
                        enterMarkWithImage(c, ex, id, d, upload);
                        return;
                    }

                    if ("notes".equals(action)) {
                        notesWithImage(c, ex, id, d, upload);
                        return;
                    }

                    response(ex, 400, "ERROR|File upload is not supported for this operation.");
                    return;
                }

                switch(action){
                    case "grades": grades(c,ex,id); break;
                    case "groups": groups(c,ex,id); break;
                    case "chat": chat(c,ex,id,d); break;
                    case "notes": notes(c,ex,id,d); break;
                    case "schedule": schedule(c,ex,id,d); break;
                    case "enterMark": enterMark(c,ex,id,d); break;
                    case "findTutor": findTutor(c,ex,id,d); break;
                    case "tutorRequests": tutorRequests(c,ex,id); break;
                    case "tutorAction": tutorAction(c,ex,id,d); break;
                    case "classTutor": classTutor(c,ex,id,d); break;
                    case "review": review(c,ex,id,d); break;
                    case "report": report(c,ex,id,d); break;
                    case "notifications": notifications(c,ex,id); break;
                    case "leaveGroup": leaveGroup(c,ex,id,d); break;
                    default: response(ex,400,"ERROR|Unknown operation.");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            response(ex,500,"ERROR|"+safe(e.getMessage()));
        }
    }

    static void teacherOperation(HttpExchange ex) {
        Map<String,String> d="GET".equalsIgnoreCase(ex.getRequestMethod())?query(ex):form(ex);
        String tid=d.get("teacherId"), action=d.get("action");
        if(blank(tid)||blank(action)){response(ex,400,"ERROR|Teacher ID and operation are required.");return;}
        try(Connection c=getConnection()){
            if(!teacherExists(c,tid)){response(ex,403,"ERROR|Invalid teacher account.");return;}
            switch(action){
                case "marks": teacherMarks(c,ex,tid); break;
                case "verify": teacherVerify(c,ex,tid,d); break;
                case "reports": teacherReports(c,ex,tid); break;
                case "reportAction": teacherReportAction(c,ex,tid,d); break;
                default: response(ex,400,"ERROR|Unknown teacher operation.");
            }
        }catch(Exception e){e.printStackTrace();response(ex,500,"ERROR|"+safe(e.getMessage()));}
    }

    static void grades(Connection c,HttpExchange ex,String id)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");
        try(PreparedStatement p=c.prepareStatement("SELECT subject_code,subject_name,mark,grade,status,COALESCE(proof_file_path,proof) FROM subject_marks WHERE student_id=? ORDER BY mark_id DESC")){p.setString(1,id);ResultSet r=p.executeQuery();while(r.next()) out.append(row(r.getString(1),r.getString(2),String.valueOf(r.getInt(3)),r.getString(4),r.getString(5),r.getString(6))).append("\n");}
        response(ex,200,out.toString());
    }

    static void groups(Connection c,HttpExchange ex,String id)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");
        String sql="SELECT g.group_id,g.group_name,g.subject_code,g.grade,(SELECT COUNT(*) FROM group_members gm2 WHERE gm2.group_id=g.group_id) members FROM study_groups g JOIN group_members gm ON g.group_id=gm.group_id WHERE gm.student_id=? ORDER BY g.group_id";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,id);ResultSet r=p.executeQuery();while(r.next())out.append(r.getInt(1)).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("|").append(r.getInt(5)).append("\n");}
        response(ex,200,out.toString());
    }

    static void chat(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        int gid=num(d.get("groupId"));
        if(gid<=0){response(ex,400,"ERROR|Choose a group first.");return;}
        if("POST".equalsIgnoreCase(ex.getRequestMethod())){
            String msg=trim(d.get("message")); if(blank(msg)){response(ex,400,"ERROR|Message cannot be empty.");return;}
            if(!member(c,gid,id)){response(ex,403,"ERROR|You are not a member of this group.");return;}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO group_messages(group_id,student_id,message_text,message_time) VALUES(?,?,?,?)")){p.setInt(1,gid);p.setString(2,id);p.setString(3,msg);p.setString(4,now());p.executeUpdate();}
            response(ex,200,"OK|Message sent."); return;
        }
        StringBuilder out=new StringBuilder("OK\n");
        try(PreparedStatement p=c.prepareStatement("SELECT gm.student_id,COALESCE(s.student_name,gm.student_id),gm.message_text,gm.message_time FROM group_messages gm LEFT JOIN students s ON s.student_id=gm.student_id WHERE gm.group_id=? ORDER BY gm.message_id")){p.setInt(1,gid);ResultSet r=p.executeQuery();while(r.next())out.append(esc(r.getString(1))).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("\n");}
        response(ex,200,out.toString());
    }

    static void notes(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        int gid=num(d.get("groupId"));
        if(gid<=0){
            response(ex,400,"ERROR|Choose a group first.");
            return;
        }
        if(!member(c,gid,id)){
            response(ex,403,"ERROR|Not a group member.");
            return;
        }

        if("POST".equalsIgnoreCase(ex.getRequestMethod())){
            String f=trim(d.get("fileName"));
            if(blank(f)){
                response(ex,400,"ERROR|Enter a note/file name.");
                return;
            }

            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO group_notes(group_id,student_id,file_name) VALUES(?,?,?)")){
                p.setInt(1,gid);
                p.setString(2,id);
                p.setString(3,f);
                p.executeUpdate();
            }

            response(ex,200,"OK|Note added.");
            return;
        }

        StringBuilder out=new StringBuilder("OK\n");
        try(PreparedStatement p=c.prepareStatement(
                "SELECT student_id,file_name,COALESCE(file_path,''),COALESCE(file_type,'') " +
                "FROM group_notes WHERE group_id=? ORDER BY note_id DESC")){
            p.setInt(1,gid);
            ResultSet r=p.executeQuery();
            while(r.next()){
                out.append(esc(r.getString(1))).append("|")
                   .append(esc(r.getString(2))).append("|")
                   .append(esc(r.getString(3))).append("|")
                   .append(esc(r.getString(4))).append("\n");
            }
        }
        response(ex,200,out.toString());
    }

    static void notesWithImage(Connection c,HttpExchange ex,String id,
                               Map<String,String>d,MultipartData upload)throws Exception{
        int gid=num(d.get("groupId"));

        if(gid<=0){
            response(ex,400,"ERROR|Choose a group first.");
            return;
        }

        if(!member(c,gid,id)){
            response(ex,403,"ERROR|Not a group member.");
            return;
        }

        if(upload == null || upload.fileBytes == null || upload.fileBytes.length == 0){
            response(ex,400,"ERROR|Choose a JPG or JPEG note image.");
            return;
        }

        validateJpeg(upload);

        String originalName=safeFileName(upload.fileName);
        String storedName=UUID.randomUUID().toString()+".jpg";
        Path file=UPLOAD_ROOT.resolve("notes").resolve(storedName);
        Files.write(file,upload.fileBytes,StandardOpenOption.CREATE_NEW);

        String filePath="/uploads/notes/"+storedName;

        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO group_notes(group_id,student_id,file_name,file_path,file_type) VALUES(?,?,?,?,?)")){
            p.setInt(1,gid);
            p.setString(2,id);
            p.setString(3,originalName);
            p.setString(4,filePath);
            p.setString(5,"image/jpeg");
            p.executeUpdate();
        }catch(Exception e){
            try{Files.deleteIfExists(file);}catch(Exception ignored){}
            throw e;
        }

        response(ex,200,"OK|Note image uploaded.|"+filePath);
    }

    static void schedule(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        int gid=num(d.get("groupId"));if(gid<=0){response(ex,400,"ERROR|Choose a group first.");return;}if(!member(c,gid,id)){response(ex,403,"ERROR|Not a group member.");return;}
        if("POST".equalsIgnoreCase(ex.getRequestMethod())){String topic=trim(d.get("topic")),date=trim(d.get("date")),time=trim(d.get("time")),loc=trim(d.get("location"));if(blank(topic)||blank(date)||blank(time)){response(ex,400,"ERROR|Topic, date and time are required.");return;}try(PreparedStatement p=c.prepareStatement("INSERT INTO group_schedules(group_id,topic,schedule_date,schedule_time,location) VALUES(?,?,?,?,?)")){p.setInt(1,gid);p.setString(2,topic);p.setString(3,date);p.setString(4,time);p.setString(5,loc);p.executeUpdate();}response(ex,200,"OK|Schedule added.");return;}
        StringBuilder out=new StringBuilder("OK\n");try(PreparedStatement p=c.prepareStatement("SELECT topic,schedule_date,schedule_time,location FROM group_schedules WHERE group_id=? ORDER BY schedule_id DESC")){p.setInt(1,gid);ResultSet r=p.executeQuery();while(r.next())out.append(esc(r.getString(1))).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("\n");}response(ex,200,out.toString());
    }

    static void enterMark(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        String code=trim(d.get("subjectCode")), proof=trim(d.get("proof"));
        int mark=num(d.get("mark"));
        String name=subjectName(code);

        if(!Arrays.asList("GAMAT301","GAEST305").contains(code)){
            response(ex,400,"ERROR|Invalid subject code.");
            return;
        }
        if(mark<0||mark>100){
            response(ex,400,"ERROR|Mark must be 0 to 100.");
            return;
        }

        String grade=grade(mark);
        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO subject_marks(student_id,subject_code,subject_name,mark,grade,proof,status) " +
                "VALUES(?,?,?,?,?,?, 'Pending Verification')")){
            p.setString(1,id);
            p.setString(2,code);
            p.setString(3,name);
            p.setInt(4,mark);
            p.setString(5,grade);
            p.setString(6,proof);
            p.executeUpdate();
        }

        response(ex,200,"OK|Mark submitted as "+grade+". Teacher verification is pending.");
    }

    static void enterMarkWithImage(Connection c,HttpExchange ex,String id,
                                    Map<String,String>d,MultipartData upload)throws Exception{
        String code=trim(d.get("subjectCode"));
        int mark=num(d.get("mark"));
        String name=subjectName(code);

        if(!Arrays.asList("GAMAT301","GAEST305").contains(code)){
            response(ex,400,"ERROR|Invalid subject code.");
            return;
        }

        if(mark<0||mark>100){
            response(ex,400,"ERROR|Mark must be 0 to 100.");
            return;
        }

        if(upload == null || upload.fileBytes == null || upload.fileBytes.length == 0){
            response(ex,400,"ERROR|Choose a JPG or JPEG proof image.");
            return;
        }

        validateJpeg(upload);

        String originalName=safeFileName(upload.fileName);
        String storedName=UUID.randomUUID().toString()+".jpg";
        Path file=UPLOAD_ROOT.resolve("proof").resolve(storedName);
        Files.write(file,upload.fileBytes,StandardOpenOption.CREATE_NEW);

        String filePath="/uploads/proof/"+storedName;
        String grade=grade(mark);

        try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO subject_marks(" +
                "student_id,subject_code,subject_name,mark,grade,proof,status,proof_file_path,proof_file_type) " +
                "VALUES(?,?,?,?,?,?, 'Pending Verification',?,?)")){
            p.setString(1,id);
            p.setString(2,code);
            p.setString(3,name);
            p.setInt(4,mark);
            p.setString(5,grade);
            p.setString(6,originalName);
            p.setString(7,filePath);
            p.setString(8,"image/jpeg");
            p.executeUpdate();
        }catch(Exception e){
            try{Files.deleteIfExists(file);}catch(Exception ignored){}
            throw e;
        }

        response(ex,200,
                "OK|Mark submitted as "+grade+". Teacher verification is pending.|"+filePath);
    }

    static void findTutor(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        String code=trim(d.get("subjectCode")),topic=trim(d.get("topic")),selected=trim(d.get("tutorId"));double amount=Double.parseDouble(blank(d.get("amount"))?"0":d.get("amount"));
        if(blank(code)||blank(topic)){response(ex,400,"ERROR|Subject and topic are required.");return;}
        String grade="";try(PreparedStatement p=c.prepareStatement("SELECT grade FROM subject_marks WHERE student_id=? AND subject_code=? AND status='Verified' ORDER BY mark_id DESC LIMIT 1")){p.setString(1,id);p.setString(2,code);ResultSet r=p.executeQuery();if(r.next())grade=r.getString(1);}
        if(blank(grade)){response(ex,400,"ERROR|You need a verified mark for this subject first.");return;}
        double max="F".equalsIgnoreCase(grade)?750:500;if(amount<0||amount>max){response(ex,400,"ERROR|Maximum allowed amount is ₹"+(int)max+".");return;}
        int gid=findStudentGroup(c,id,code,grade);if(gid<=0){response(ex,400,"ERROR|Your verified subject has not been assigned to a group yet.");return;}
        if(!blank(selected)&&!isTutorForSubject(c,selected,code)){response(ex,400,"ERROR|Selected tutor is not an S-grade tutor for this subject.");return;}
        try(PreparedStatement p=c.prepareStatement("INSERT INTO tutor_requests(student_id,tutor_id,group_name,subject_code,topic,amount,status) SELECT ?,?,?,?, ?,?, 'Pending' FROM study_groups WHERE group_id=?")){p.setString(1,id);if(blank(selected))p.setNull(2,Types.VARCHAR);else p.setString(2,selected);p.setString(3,groupName(c,gid));p.setString(4,code);p.setString(5,topic);p.setDouble(6,amount);p.setInt(7,gid);p.executeUpdate();}
        response(ex,200,"OK|Tutor request sent.");
    }

    static void tutorRequests(Connection c,HttpExchange ex,String id)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");
        String sql="SELECT tr.request_id,tr.student_id,COALESCE(s.student_name,''),COALESCE(tr.tutor_id,''),COALESCE(t.teacher_name,''),tr.subject_code,tr.topic,tr.amount,tr.status FROM tutor_requests tr JOIN students s ON s.student_id=tr.student_id LEFT JOIN teachers t ON t.teacher_id=tr.tutor_id WHERE tr.student_id=? OR tr.tutor_id=? OR (tr.tutor_id IS NULL AND EXISTS(SELECT 1 FROM subject_marks sm WHERE sm.student_id=? AND sm.subject_code=tr.subject_code AND sm.grade='S' AND sm.status='Verified')) ORDER BY tr.request_id DESC";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,id);p.setString(2,id);p.setString(3,id);ResultSet r=p.executeQuery();while(r.next())out.append(r.getInt(1)).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("|").append(esc(r.getString(5))).append("|").append(esc(r.getString(6))).append("|").append(esc(r.getString(7))).append("|").append(r.getDouble(8)).append("|").append(esc(r.getString(9))).append("\n");}
        response(ex,200,out.toString());
    }

    static void tutorAction(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        int rid=num(d.get("requestId"));String act=trim(d.get("decision"));
        if(!"accept".equals(act)&&!"reject".equals(act)){response(ex,400,"ERROR|Invalid tutor decision.");return;}
        String code=null,student=null,currentTutor=null,status=null;int gid=0;
        try(PreparedStatement p=c.prepareStatement("SELECT student_id,tutor_id,subject_code,status,group_name FROM tutor_requests WHERE request_id=?")){p.setInt(1,rid);ResultSet r=p.executeQuery();if(!r.next()){response(ex,404,"ERROR|Request not found.");return;}student=r.getString(1);currentTutor=r.getString(2);code=r.getString(3);status=r.getString(4);gid=findGroupIdByName(c,r.getString(5));}
        if(!"Pending".equalsIgnoreCase(status)){response(ex,400,"ERROR|This request is no longer pending.");return;}
        boolean allowed=isTutorForSubject(c,id,code) && (blank(currentTutor)||id.equals(currentTutor));if(!allowed){response(ex,403,"ERROR|Only an eligible S-grade tutor can handle this request.");return;}
        if("accept".equals(act)){
            try(PreparedStatement p=c.prepareStatement("UPDATE tutor_requests SET tutor_id=?,status='Accepted - Pay via GPay / meet via Google Meet' WHERE request_id=?")){p.setString(1,id);p.setInt(2,rid);p.executeUpdate();}
            notify(c,student,"Your tutor request was accepted. Contact the tutor and arrange payment through GPay and the session through Google Meet.");
        }else{try(PreparedStatement p=c.prepareStatement("UPDATE tutor_requests SET tutor_id=?,status='Rejected' WHERE request_id=?")){p.setString(1,id);p.setInt(2,rid);p.executeUpdate();}notify(c,student,"Your tutor request was rejected by the tutor.");}
        response(ex,200,"OK|Request updated.");
    }

    static void classTutor(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");
        try(PreparedStatement p=c.prepareStatement("SELECT t.teacher_id,t.teacher_name,s.class_name,s.department FROM students s JOIN teachers t ON t.teacher_id=s.class_tutor_id WHERE s.student_id=?")){p.setString(1,id);ResultSet r=p.executeQuery();if(r.next())out.append(r.getString(1)).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("\n");}
        response(ex,200,out.toString());
    }

    static void review(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        String target=trim(d.get("target")),text=trim(d.get("reviewText"));if(blank(target)||blank(text)){response(ex,400,"ERROR|Target and review are required.");return;}try(PreparedStatement p=c.prepareStatement("INSERT INTO reviews_data(student_id,target,review_text) VALUES(?,?,?)")){p.setString(1,id);p.setString(2,target);p.setString(3,text);p.executeUpdate();}response(ex,200,"OK|Anonymous review submitted.");
    }

    static void report(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{
        String target=trim(d.get("reportedId")),reason=trim(d.get("reason"));if(blank(target)||blank(reason)){response(ex,400,"ERROR|Student ID and reason are required.");return;}if(id.equalsIgnoreCase(target)){response(ex,400,"ERROR|You cannot report yourself.");return;}if(!shareAnyGroup(c,id,target)){response(ex,403,"ERROR|You can report only a member of one of your groups.");return;}try(PreparedStatement p=c.prepareStatement("INSERT INTO reports_data(reporter_id,reported_id,reason,status) VALUES(?,?,?,'Pending')")){p.setString(1,id);p.setString(2,target);p.setString(3,reason);p.executeUpdate();}response(ex,200,"OK|Report submitted for teacher review.");
    }

    static void notifications(Connection c,HttpExchange ex,String id)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");try(PreparedStatement p=c.prepareStatement("SELECT message,notification_time FROM notifications WHERE student_id=? ORDER BY notification_id DESC")){p.setString(1,id);ResultSet r=p.executeQuery();while(r.next())out.append(esc(r.getString(1))).append("|").append(esc(r.getString(2))).append("\n");}response(ex,200,out.toString());
    }

    static void leaveGroup(Connection c,HttpExchange ex,String id,Map<String,String>d)throws Exception{int gid=num(d.get("groupId"));if(gid<=0){response(ex,400,"ERROR|Choose a group.");return;}if(!member(c,gid,id)){response(ex,403,"ERROR|You are not a member of this group.");return;}try(PreparedStatement p=c.prepareStatement("DELETE FROM group_members WHERE group_id=? AND student_id=?")){p.setInt(1,gid);p.setString(2,id);p.executeUpdate();}response(ex,200,"OK|You left the group.");}

    static void teacherMarks(Connection c,HttpExchange ex,String tid)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");String sql="SELECT sm.mark_id,sm.student_id,s.student_name,sm.subject_code,sm.subject_name,sm.mark,sm.grade,COALESCE(sm.proof_file_path,sm.proof),sm.status FROM subject_marks sm JOIN students s ON s.student_id=sm.student_id WHERE s.class_tutor_id=? AND sm.status='Pending Verification' ORDER BY sm.mark_id";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,tid);ResultSet r=p.executeQuery();while(r.next())out.append(r.getInt(1)).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("|").append(esc(r.getString(5))).append("|").append(r.getInt(6)).append("|").append(esc(r.getString(7))).append("|").append(esc(r.getString(8))).append("|").append(esc(r.getString(9))).append("\n");}response(ex,200,out.toString());
    }

    static void teacherVerify(Connection c, HttpExchange ex, String tid, Map<String,String> d) throws Exception {

    int markId = num(d.get("markId"));
    String decision = trim(d.get("decision"));

    if (markId <= 0 ||
        (!"verify".equals(decision) && !"reject".equals(decision))) {

        response(ex, 400, "ERROR|Invalid verification request.");
        return;
    }

    String sid = null;

    // Make sure this mark belongs to a student whose class tutor
    // is the currently logged-in teacher.
    try (PreparedStatement q = c.prepareStatement(
            "SELECT sm.student_id " +
            "FROM subject_marks sm " +
            "JOIN students s ON s.student_id=sm.student_id " +
            "WHERE sm.mark_id=? " +
            "AND s.class_tutor_id=? " +
            "AND sm.status='Pending Verification'")) {

        q.setInt(1, markId);
        q.setString(2, tid);

        try (ResultSet r = q.executeQuery()) {

            if (!r.next()) {
                response(ex, 403,
                        "ERROR|Mark not found or not assigned to this teacher.");
                return;
            }

            sid = r.getString("student_id");
        }
    }

    String status =
            "verify".equals(decision)
                    ? "Verified"
                    : "Rejected";

    // Update the mark status
    try (PreparedStatement p = c.prepareStatement(
            "UPDATE subject_marks SET status=? WHERE mark_id=?")) {

        p.setString(1, status);
        p.setInt(2, markId);
        p.executeUpdate();
    }

    if ("Verified".equals(status)) {

        String code = null;
        String gradeValue = null;

        // Get the subject and grade of the verified mark
        try (PreparedStatement q = c.prepareStatement(
                "SELECT subject_code, grade " +
                "FROM subject_marks " +
                "WHERE mark_id=?")) {

            q.setInt(1, markId);

            try (ResultSet r = q.executeQuery()) {

                if (!r.next()) {
                    response(ex, 500,
                            "ERROR|Verified mark could not be found.");
                    return;
                }

                code = r.getString("subject_code");
                gradeValue = r.getString("grade");
            }
        }

        // Automatically find/create the correct group
        String assignedGroup =
                ensureGroup(c, sid, code, gradeValue);

        // Verify that the student was actually added
        int groupId =
                findStudentGroup(c, sid, code, gradeValue);

        if (groupId <= 0) {
            response(ex, 500,
                    "ERROR|Mark verified, but group assignment failed.");
            return;
        }

        notify(
                c,
                sid,
                "Your mark has been verified. You were assigned to " +
                assignedGroup + "."
        );

        response(
                ex,
                200,
                "OK|Mark verified. Student assigned to " +
                assignedGroup + "."
        );

    } else {

        notify(
                c,
                sid,
                "Your mark was rejected by the class tutor. Please contact the tutor."
        );

        response(
                ex,
                200,
                "OK|Mark rejected."
        );
    }
}

    static void teacherReports(Connection c,HttpExchange ex,String tid)throws Exception{
        StringBuilder out=new StringBuilder("OK\n");String sql="SELECT r.report_id,r.reporter_id,COALESCE(a.student_name,''),r.reported_id,COALESCE(b.student_name,''),r.reason,r.status,b.warnings FROM reports_data r JOIN students a ON a.student_id=r.reporter_id JOIN students b ON b.student_id=r.reported_id WHERE a.class_tutor_id=? OR b.class_tutor_id=? ORDER BY r.report_id DESC";
        try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,tid);p.setString(2,tid);ResultSet r=p.executeQuery();while(r.next())out.append(r.getInt(1)).append("|").append(esc(r.getString(2))).append("|").append(esc(r.getString(3))).append("|").append(esc(r.getString(4))).append("|").append(esc(r.getString(5))).append("|").append(esc(r.getString(6))).append("|").append(esc(r.getString(7))).append("|").append(r.getInt(8)).append("\n");}response(ex,200,out.toString());
    }

    static void teacherReportAction(Connection c,HttpExchange ex,String tid,Map<String,String>d)throws Exception{
        int rid=num(d.get("reportId"));String decision=trim(d.get("decision"));if(rid<=0||(!"valid".equals(decision)&&!"invalid".equals(decision))){response(ex,400,"ERROR|Invalid report decision.");return;}
        String reported=null;try(PreparedStatement q=c.prepareStatement("SELECT r.reported_id FROM reports_data r JOIN students b ON b.student_id=r.reported_id WHERE r.report_id=? AND (b.class_tutor_id=? OR EXISTS(SELECT 1 FROM students a WHERE a.student_id=r.reporter_id AND a.class_tutor_id=?)) AND r.status='Pending'")){q.setInt(1,rid);q.setString(2,tid);q.setString(3,tid);ResultSet r=q.executeQuery();if(!r.next()){response(ex,403,"ERROR|Report not found or already reviewed.");return;}reported=r.getString(1);}
        String status="valid".equals(decision)?"Valid":"Invalid";try(PreparedStatement p=c.prepareStatement("UPDATE reports_data SET status=? WHERE report_id=?")){p.setString(1,status);p.setInt(2,rid);p.executeUpdate();}
        if("Valid".equals(status)){int warnings=0;try(PreparedStatement q=c.prepareStatement("UPDATE students SET warnings=warnings+1, account_status=CASE WHEN warnings+1>=3 THEN 'DELETED' ELSE account_status END WHERE student_id=?")){q.setString(1,reported);q.executeUpdate();}try(PreparedStatement q=c.prepareStatement("SELECT warnings FROM students WHERE student_id=?")){q.setString(1,reported);ResultSet r=q.executeQuery();if(r.next())warnings=r.getInt(1);}notify(c,reported,"A report against your account was reviewed as valid. Warning "+warnings+" of 3.");if(warnings>=3)notify(c,reported,"Your account has been deactivated after 3 valid reports.");}
        response(ex,200,"OK|Report marked "+status.toLowerCase()+".");
    }

    static String ensureGroup(Connection c, String sid, String code, String grade) throws Exception {

    // S-grade students go into the Super Group
    if ("S".equalsIgnoreCase(grade)) {

        String name = subjectName(code) + " Super Group";

        int gid = findGroupIdByName(c, name);

        if (gid <= 0) {
            try (PreparedStatement p=c.prepareStatement("INSERT INTO study_groups(group_name,subject_code,subject_name,grade) VALUES(?,?,?,?)",Statement.RETURN_GENERATED_KEYS)) {

                p.setString(1,name);
                p.setString(2,code);
                p.setString(3,subjectName(code));
                p.setString(4,grade);

                p.executeUpdate();     

                try (ResultSet r = p.getGeneratedKeys()) {
                    if (r.next()) {
                        gid = r.getInt(1);
                    }
                }
            }
        }

        addMember(c, gid, sid);

        return name;
    }

    // For normal grades, first look for an existing group
    // with fewer than 10 members.
    String sql =
            "SELECT g.group_id, g.group_name, " +
            "(SELECT COUNT(*) FROM group_members gm2 " +
            " WHERE gm2.group_id=g.group_id) AS member_count " +
            "FROM study_groups g " +
            "WHERE g.subject_code=? AND g.grade=? " +
            "ORDER BY g.group_id";

    try (PreparedStatement p = c.prepareStatement(sql)) {

        p.setString(1, code);
        p.setString(2, grade);

        try (ResultSet r = p.executeQuery()) {

            while (r.next()) {

                int gid = r.getInt("group_id");
                String groupName = r.getString("group_name");
                int members = r.getInt("member_count");

                // Existing group has space
                if (members < 10) {

                    addMember(c, gid, sid);

                    return groupName;
                }
            }
        }
    }

    // No suitable group exists, so create a new one.
    String name =
            subjectName(code) + " " + grade +
            " Group " + System.currentTimeMillis();

    int gid;

    try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO study_groups(group_name,subject_code,subject_name,grade) VALUES(?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {

        p.setString(1, name);
        p.setString(2, code);
        p.setString(3, subjectName(code));
        p.setString(4, grade);

        p.executeUpdate();

        try (ResultSet r = p.getGeneratedKeys()) {

            if (!r.next()) {
                throw new SQLException("Could not create study group.");
            }

            gid = r.getInt(1);
        }
    }

    // Add student to the newly created group
    addMember(c, gid, sid);

    return name;
}

    static class MultipartData {
        Map<String,String> fields = new HashMap<>();
        byte[] fileBytes;
        String fileName;
        String contentType;
    }

    static boolean isMultipart(HttpExchange ex){
        String ct=ex.getRequestHeaders().getFirst("Content-Type");
        return ct!=null && ct.toLowerCase().startsWith("multipart/form-data");
    }

    static MultipartData parseMultipart(HttpExchange ex)throws Exception{
        String contentType=ex.getRequestHeaders().getFirst("Content-Type");
        int boundaryPos=contentType.indexOf("boundary=");
        if(boundaryPos<0){
            throw new IllegalArgumentException("Missing multipart boundary.");
        }

        String boundary=contentType.substring(boundaryPos+"boundary=".length()).trim();
        if(boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length()>1){
            boundary=boundary.substring(1,boundary.length()-1);
        }

        byte[] body=ex.getRequestBody().readAllBytes();

        // 2 MB image + a small amount of multipart overhead.
        if(body.length > MAX_IMAGE_SIZE + 64 * 1024L){
            throw new IllegalArgumentException("File is too large. Maximum size is 2 MB.");
        }

        byte[] boundaryBytes=("--"+boundary).getBytes(StandardCharsets.ISO_8859_1);
        MultipartData result=new MultipartData();

        int pos=indexOf(body,boundaryBytes,0);

        while(pos>=0){
            int partStart=pos+boundaryBytes.length;

            if(partStart+2<=body.length && body[partStart]=='-' && body[partStart+1]=='-'){
                break;
            }

            if(partStart+2<=body.length && body[partStart]=='\r' && body[partStart+1]=='\n'){
                partStart+=2;
            }

            int headerEnd=indexOf(body,new byte[]{'\r','\n','\r','\n'},partStart);
            if(headerEnd<0) break;

            String headers=new String(body,partStart,headerEnd-partStart,StandardCharsets.ISO_8859_1);
            int dataStart=headerEnd+4;

            int nextBoundary=indexOf(body,boundaryBytes,dataStart);
            if(nextBoundary<0) break;

            int dataEnd=nextBoundary;
            if(dataEnd>=2 && body[dataEnd-2]=='\r' && body[dataEnd-1]=='\n'){
                dataEnd-=2;
            }

            String disposition=getHeader(headers,"Content-Disposition");
            if(disposition!=null){
                String fieldName=getDispositionValue(disposition,"name");
                String fileName=getDispositionValue(disposition,"filename");

                if(fileName!=null && !fileName.isEmpty()){
                    result.fileName=fileName;
                    result.contentType=getHeader(headers,"Content-Type");
                    result.fileBytes=Arrays.copyOfRange(body,dataStart,dataEnd);

                    if(result.fileBytes.length>MAX_IMAGE_SIZE){
                        throw new IllegalArgumentException("File is too large. Maximum size is 2 MB.");
                    }
                }else if(fieldName!=null){
                    String value=new String(body,dataStart,dataEnd-dataStart,StandardCharsets.UTF_8);
                    result.fields.put(fieldName,value);
                }
            }

            pos=nextBoundary;
        }

        return result;
    }

    static String getHeader(String headers,String wanted){
        for(String line:headers.split("\r\n")){
            int colon=line.indexOf(':');
            if(colon>0 && line.substring(0,colon).trim().equalsIgnoreCase(wanted)){
                return line.substring(colon+1).trim();
            }
        }
        return null;
    }

    static String getDispositionValue(String disposition,String key){
        String marker=key+"=\"";
        int start=disposition.indexOf(marker);
        if(start<0) return null;
        start+=marker.length();
        int end=disposition.indexOf('"',start);
        return end<0 ? disposition.substring(start) : disposition.substring(start,end);
    }

    static int indexOf(byte[] data,byte[] target,int from){
        if(target.length==0) return from;
        outer:
        for(int i=Math.max(0,from);i<=data.length-target.length;i++){
            for(int j=0;j<target.length;j++){
                if(data[i+j]!=target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static String safeFileName(String name){
        if(blank(name)) return "uploaded-image.jpg";
        String cleaned=name.replace("\\","/").trim();
        int slash=cleaned.lastIndexOf('/');
        if(slash>=0) cleaned=cleaned.substring(slash+1);
        cleaned=cleaned.replaceAll("[^A-Za-z0-9._-]","_");
        if(cleaned.length()>180) cleaned=cleaned.substring(cleaned.length()-180);
        return cleaned;
    }

    static void validateJpeg(MultipartData upload){
        String name=safeFileName(upload.fileName).toLowerCase();
        String type=trim(upload.contentType).toLowerCase();

        boolean extension=name.endsWith(".jpg") || name.endsWith(".jpeg");
        boolean mime=blank(type) || "image/jpeg".equals(type);
        byte[] b=upload.fileBytes;

        boolean jpegSignature=b.length>=3
                && (b[0]&0xFF)==0xFF
                && (b[1]&0xFF)==0xD8
                && (b[2]&0xFF)==0xFF;

        if(!extension || !mime || !jpegSignature){
            throw new IllegalArgumentException("Only JPG/JPEG image files are allowed.");
        }
    }

    static boolean studentExists(Connection c,String id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM students WHERE student_id=? AND account_status='ACTIVE'")){p.setString(1,id);return p.executeQuery().next();}}
    static boolean teacherExists(Connection c,String id)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM teachers WHERE teacher_id=? AND verified=TRUE")){p.setString(1,id);return p.executeQuery().next();}}
    static boolean member(Connection c,int gid,String sid)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM group_members WHERE group_id=? AND student_id=?")){p.setInt(1,gid);p.setString(2,sid);return p.executeQuery().next();}}
    static void addMember(Connection c, int gid, String sid) throws SQLException {
    if (member(c, gid, sid)) {
        return;
    }

    String sql = "INSERT INTO group_members(group_id, student_id) VALUES(?, ?)";

    try (PreparedStatement p = c.prepareStatement(sql)) {
        p.setInt(1, gid);
        p.setString(2, sid);
        p.executeUpdate();
    }

    if (!member(c, gid, sid)) {
        throw new SQLException("Student was not added to group_members.");
    }
}
    static String groupName(Connection c,int gid)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT group_name FROM study_groups WHERE group_id=?")){p.setInt(1,gid);ResultSet r=p.executeQuery();return r.next()?r.getString(1):"";}}
    static int findGroupIdByName(Connection c,String name)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT group_id FROM study_groups WHERE group_name=?")){p.setString(1,name);ResultSet r=p.executeQuery();return r.next()?r.getInt(1):0;}}
    static int findStudentGroup(Connection c,String sid,String code,String grade)throws SQLException{try(PreparedStatement p=c.prepareStatement("SELECT g.group_id FROM study_groups g JOIN group_members gm ON gm.group_id=g.group_id WHERE gm.student_id=? AND g.subject_code=? AND g.grade=? LIMIT 1")){p.setString(1,sid);p.setString(2,code);p.setString(3,grade);ResultSet r=p.executeQuery();return r.next()?r.getInt(1):0;}}
    static boolean isTutorForSubject(Connection c,String sid,String code)throws SQLException{String sql="SELECT 1 FROM subject_marks WHERE student_id=? AND subject_code=? AND grade='S' AND status='Verified' LIMIT 1";try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,sid);p.setString(2,code);return p.executeQuery().next();}}
    static boolean shareAnyGroup(Connection c,String a,String b)throws SQLException{String sql="SELECT 1 FROM group_members x JOIN group_members y ON x.group_id=y.group_id WHERE x.student_id=? AND y.student_id=? LIMIT 1";try(PreparedStatement p=c.prepareStatement(sql)){p.setString(1,a);p.setString(2,b);return p.executeQuery().next();}}
    static void notify(Connection c,String sid,String msg)throws SQLException{try(PreparedStatement p=c.prepareStatement("INSERT INTO notifications(student_id,message,notification_time) VALUES(?,?,?)")){p.setString(1,sid);p.setString(2,msg);p.setString(3,now());p.executeUpdate();}}

    static String subjectName(String code){return "GAMAT301".equalsIgnoreCase(code)?"Mathematics":"GAEST305".equalsIgnoreCase(code)?"DEL":code;}
    static String grade(int m){if(m>90)return "S";if(m>=80)return "A+";if(m>=70)return "A";if(m>=60)return "B+";if(m>=50)return "B";if(m>=45)return "C+";if(m>=40)return "C";if(m>=30)return "D";return "F";}
    static String now(){return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());}
    static String row(String...a){StringBuilder b=new StringBuilder();for(int i=0;i<a.length;i++){if(i>0)b.append('|');b.append(esc(a[i]));}return b.toString();}
    static String esc(String s){return s==null?"":s.replace("\\","\\\\").replace("|","\\p").replace("\n"," ").replace("\r"," ");}
    static String safe(String s){return s==null?"Unknown error":s.replace("|","/").replace("\n"," ");}
    static String trim(String s){return s==null?"":s.trim();}
    static boolean blank(String s){return s==null||s.trim().isEmpty();}
    static int num(String s){try{return Integer.parseInt(trim(s));}catch(Exception e){return 0;}}

    static Map<String,String> form(HttpExchange ex){return parse(ex,"POST");}
    static Map<String,String> query(HttpExchange ex){return parse(ex,"GET");}
    static Map<String,String> parse(HttpExchange ex,String type){
        String body="";try{body="GET".equals(type)?ex.getRequestURI().getRawQuery():new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);}catch(Exception ignored){}
        Map<String,String> m=new HashMap<>();if(body==null)return m;for(String pair:body.split("&")){if(pair.isEmpty())continue;String[] x=pair.split("=",2);String k=URLDecoder.decode(x[0],StandardCharsets.UTF_8);String v=x.length>1?URLDecoder.decode(x[1],StandardCharsets.UTF_8):"";m.put(k,v);}return m;
    }
    static void response(HttpExchange ex,int status,String text){try{byte[] b=text.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","text/plain; charset=UTF-8");ex.getResponseHeaders().set("Cache-Control","no-store");ex.sendResponseHeaders(status,b.length);try(OutputStream o=ex.getResponseBody()){o.write(b);}}catch(Exception ignored){}}
}