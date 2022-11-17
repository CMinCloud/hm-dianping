package com.hmdp.utils;
import com.hmdp.dto.UserDTO;

public class UserHolder {
    private static final ThreadLocal<UserDTO> LOCAL = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        LOCAL.set(user);
    }

    public static UserDTO getUser(){
        return LOCAL.get();
    }

    public static void removeUser(){
        LOCAL.remove();
    }
}
