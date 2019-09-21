import ballerina/io;
import ballerina/http;
import ballerina/log;

service hello on new http:Listener(9090) {

    resource function sayHello(http:Caller caller, http:Request req) {

        var result = caller->respond("Hello, World!");
        log:printInfo("All went well");

        if (result is error) {
            log:printError("Error sending response",err = result);
        }
    }
}