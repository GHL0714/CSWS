package com.example.csws.service.instance;

import com.example.csws.common.shRunner.ParserResponseDto;
import com.example.csws.common.shRunner.ShParser;
import com.example.csws.common.shRunner.ShRunner;
import com.example.csws.entity.boundPolicy.InboundPolicy;
import com.example.csws.entity.instance.Instance;
import com.example.csws.entity.instance.InstanceDto;
import com.example.csws.entity.server.Server;
import com.example.csws.entity.user.User;
import com.example.csws.repository.boundPolicy.InboundPolicyRepository;
import com.example.csws.repository.instance.InstanceRepository;
import com.example.csws.repository.server.ServerRepository;
import com.example.csws.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class InstanceServiceImpl implements InstanceService{

    private final UserRepository userRepository;
    private final ServerRepository serverRepository;
    private final InstanceRepository instanceRepository;
    private final InboundPolicyRepository inboundPolicyRepository;
    private final EntityManager entityManager;
    private final ShRunner shRunner;
    private final ShParser shParser;
    // 컨트롤러에서 이미 인스턴스의 code 필드를 받아왔음을 가정함.
    // DB에서 프로시저를 이용하여 code를 설정할 것이라면 연관된 전체 기능 수정 필요.
    // dto를 entity로 변환 후 save(insert 혹은 update)한 결과값을 컨트롤러에 반환.
    // 1) 호스트 포트는 유저가 선택한 포트. 2) 컨테이너 포트는 22 고정.
    // 3) 유저 이름 : 유저가 입력한 이름(instance.getName())
    // 4) 유저 코드 : instanceId로 대체.
    // 5) 용량. 6) 이미지 이름
    @Transactional
    @Override
    public String createInstance(InstanceDto instanceDto, String username) {
        // port null 로 저장
        User newUser = userRepository.getReferenceById(instanceDto.getUserId());
        Server baseServer = serverRepository.findById(instanceDto.getServerId()).get();
        Instance entity = instanceRepository.save(instanceDto.toEntity(newUser, baseServer));

        // ssh port 값 생성
        int instanceId = entity.getId();
        int port = 2000 + instanceId;

        // ssh port 값 저장
        entity.updateInstancePort(port);

        // 1 서버에서 사용하는 계정명(serverUsername), 2 서버 ip 주소, 3 호스트 포트, 4 컨테이너 포트(항상 22),
        // 5 인스턴스 name, 6 인스턴스 id, 7 용량, 8 OS
        try {
            Map result = shRunner.execCommand("CreateContainer.sh", baseServer.getServerUsername(), baseServer.getIpv4(),
                    Integer.toString(entity.getPort()), "22",
                    username, Integer.toString(entity.getId()),
                    Double.toString(entity.getStorage()), entity.getOs());

            if (!shParser.isSuccess(result.get(1).toString())) { // TODO: 실패시 엔티티 삭제해야함
                return "failure";
            }
        } catch (Exception e) {
            return e.toString();
        }

        // 인바운드 추가
        InboundPolicy inboundPolicy = InboundPolicy.builder()
                .instance(entity)
                .instancePort(22)
                .hostPort(entity.getPort())
                .build();
        inboundPolicyRepository.save(inboundPolicy);

        // TODO: 퍼블릭 키 호스트 서버로 전송
        try {
            Map result = shRunner.execCommand("SendPublickey.sh", baseServer.getServerUsername(), baseServer.getIpv4(),
                    entity.getName() + Integer.toString(entity.getId()),
                    entity.getKeyName());

            if (!shParser.isSuccess(result.get(1).toString())) { // TODO: 실패시 엔티티 삭제해야함
                return "failure";
            }
        } catch (Exception e) {
            return e.toString();
        }

        // TODO: 퍼블릭 키 호스트 서버에서 인스턴스에 등록
        try {
            Map result = shRunner.execCommand("H_SendPublickey.sh",
                    username + Integer.toString(entity.getId()),
                    entity.getKeyName());

            if (shParser.isSuccess(result.get(1).toString())) {
                instanceRepository.deleteById(entity.getId());
                return "success";
            }
            return "failure";
        } catch (Exception e) {
            return e.toString();
        }

    }

    // 인스턴스 id를 이용해 개별 인스턴스 검색 후 Optional에 넣어서 반환. 객체가 없으면 null이 담겨있음.
    @Override
    public Optional<InstanceDto> findById(int instanceId) {
        return Optional.ofNullable(instanceRepository.findById(instanceId).get().toDto());
    }

    // userId로 엔티티 리스트를 받아온 뒤, 각 엔티티를 dto로 변환하고 dto 리스트에 추가하여 반환.
    @Override
    public List<InstanceDto> findAllByUserId(Long userId) {
        List<Instance> entityList = instanceRepository.findAllByUserId(userId);
        List<InstanceDto> dtoList = new ArrayList<>();
        for (Instance entity : entityList) {
            dtoList.add(entity.toDto());
        }
        return dtoList;
    }

    // serverId로 전체 리스트 가져오기.
    @Override
    public List<InstanceDto> findAllByServerId(int serverId) {
        List<Instance> entityList = instanceRepository.findAllByServerId(serverId);
        List<InstanceDto> dtoList = new ArrayList<>();
        for (Instance entity : entityList) {
            dtoList.add(entity.toDto());
        }
        return dtoList;
    }

    // 실행할 스크립트 파일 이름과 필요 인수를 넘겨주고 실행. 실패 시 예외 문구가 뜬다.
    // 쉘에서 인수로 받는 컨테이너 이름 : instance 테이블의 name + instanceId
    @Transactional
    @Override
    public Boolean startInstance(int instanceId) {
        // instanceId를 이용해서 인스턴스와 서버 정보 가져옴
        Instance entity = instanceRepository.findById(instanceId).get();
        Server baseServer = entity.getServer();

        // db 에서 상태 업데이트
        entityManager.persist(entity);
        entity.updateStatus("running");

        // 쉘 실행
        // 1 서버에서 사용하는 계정명(serverUsername), 2 서버 ip 주소,
        // 3 컨테이너_이름(인스턴스 name + 인스턴스 id)을 인수로 넘겨준다.
        try {
            Map result = shRunner.execCommand("StartContainer.sh", baseServer.getServerUsername(),
                    baseServer.getIpv4(), entity.getName() + entity.getId());

            return shParser.isSuccess(result.get(1).toString());
        } catch (Exception e) {
            System.out.println(e);
            return false;
        }
    }
    @Transactional
    @Override
    public Boolean stopInstance(int instanceId) {
        Instance entity = instanceRepository.findById(instanceId).get();
        Server baseServer = entity.getServer();

        // db 에서 상태 업데이트
        entityManager.persist(entity);
        entity.updateStatus("stopped");

        // 쉘 실행
        try {
            Map result = shRunner.execCommand("StopContainer.sh", baseServer.getServerUsername(),
                    baseServer.getIpv4(), entity.getName() + entity.getId());

            return shParser.isSuccess(result.get(1).toString());
        } catch (Exception e) {
            System.out.println(e);
            return false;
        }
    }
    @Transactional
    @Override
    public Boolean restartInstance(int instanceId) {
        Instance entity = instanceRepository.findById(instanceId).get();
        Server baseServer = entity.getServer();

        // db 에서 상태 업데이트
        entityManager.persist(entity);
        entity.updateStatus("running");

        // 쉘 실행
        try {
            Map result = shRunner.execCommand("RestartContainer.sh", baseServer.getServerUsername(),
                    baseServer.getIpv4(), entity.getName() + entity.getId());

            return shParser.isSuccess(result.get(1).toString());
        } catch (Exception e) {
            System.out.println(e);
            return false;
        }
    }
    @Transactional
    @Override
    public Boolean deleteInstance(int instanceId) {
        Instance entity = instanceRepository.findById(instanceId).get();
        Server baseServer = entity.getServer();

        // 쉘 실행
        try {
            // 인스턴스 제거
            Map result = shRunner.execCommand("RemoveContainer.sh", baseServer.getServerUsername(),
                    baseServer.getIpv4(), entity.getName() + entity.getId());

            return shParser.isSuccess(result.get(1).toString());
        } catch (Exception e) {
            System.out.println(e);
            return false;
        }
    }

    // csws에 파일이 생성되는 경로 : ~/keys/서버_계정명/사용자_입력_키_이름.pem, pub
    @Override
    public String createKeyPair(String hostName, String keyName) {
        try {
            Map result = shRunner.execCommand("CreateKeypairs.sh", hostName, keyName);
            if(shParser.isSuccess(result.get(1).toString())) {
                return "success";
            }
            return "failure";
        } catch (Exception e) {
            return e.toString();
        }
    }

    // userId가 수정되어있는 dto를 컨트롤러에서 받은 뒤 엔티티로 변환해 save(update).
    // dto 내부의 새로운 user의 email로 user 엔티티 조회 후 toEntity() 메서드 파라미터로 넘겨주기.
    @Override
    public InstanceDto changeUserid(InstanceDto instanceDto) {
        // getReferenceById 메서드에 필요한 인자는 Long인데 우리가 설정한 엔티티는 id가 int로 되어있음.
        User newUser = userRepository.getReferenceById((long) instanceDto.getUserId());
        Server baseServer = serverRepository.findById(instanceDto.getServerId()).get();
        Instance entity = instanceRepository.save(instanceDto.toEntity(newUser, baseServer));

        return entity.toDto();
    }


    // 에러 발생시 에러 출력 및 false 값 담긴 responseDto 반환
    // 성공시 데이터 담긴 dto 반환
    @Override
    public ParserResponseDto checkContainerResource(String hostName, String hostIp, String containerName) {

        try {
            Map result = shRunner.execCommand("CheckContainerResource.sh", hostName, hostIp, containerName);
            return shParser.checkContainerResource(result.get(1).toString());
        } catch (Exception e) {
            ParserResponseDto responseDto = new ParserResponseDto();
            responseDto.setSuccess(false);
            System.out.println(e.toString());
            return responseDto;
        }
    }

    @Override
    public ParserResponseDto checkServerResource(String hostName, String hostIp) {

        try {
            Map result = shRunner.execCommand("CheckServerResource.sh", hostName, hostIp);
            return shParser.checkContainerResource(result.get(1).toString());
        } catch (Exception e) {
            ParserResponseDto responseDto = new ParserResponseDto();
            responseDto.setSuccess(false);
            System.out.println(e.toString());
            return responseDto;
        }
    }

    @Override
    public ParserResponseDto printStatusforManager(String hostName, String hostIp) {

        try {
            Map result = shRunner.execCommand("PrintStatusforManager.sh", hostName, hostIp);
            return shParser.checkContainerResource(result.get(1).toString());
        } catch (Exception e) {
            ParserResponseDto responseDto = new ParserResponseDto();
            responseDto.setSuccess(false);
            System.out.println(e.toString());
            return responseDto;
        }
    }

    @Override
    public ParserResponseDto printStatusforUser(String hostName, String hostIp, String username) {

        try {
            Map result = shRunner.execCommand("PrintStatusforUser.sh", hostName, hostIp, username);
            return shParser.checkContainerResource(result.get(1).toString());
        } catch (Exception e) {
            ParserResponseDto responseDto = new ParserResponseDto();
            responseDto.setSuccess(false);
            System.out.println(e.toString());
            return responseDto;
        }
    }

}
