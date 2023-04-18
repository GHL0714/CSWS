package com.example.csws.entity.user;

import lombok.*;

@Builder
@Getter
@Setter
@NoArgsConstructor  // 매개변수가 없는 생성자
@AllArgsConstructor // 모든 필드를 매개변수로 받는 생성자
public class UserDto {

    private int id;
    private String username;
    private String password;
    private String Role;
    private String phone;
    private String approval;
    private int universityId;
    private int departmentId;

    public User toEntity() {
        return User.builder()
                .username(username)
                .password(password)
                .roles(Role)
                .approval(approval)
                .departmentId(departmentId)
                .universityId(universityId)
                .build();
    }
}
