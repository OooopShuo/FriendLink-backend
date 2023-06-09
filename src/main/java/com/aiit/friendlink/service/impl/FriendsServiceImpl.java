package com.aiit.friendlink.service.impl;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.aiit.friendlink.common.ErrorCode;
import com.aiit.friendlink.exception.BusinessException;
import com.aiit.friendlink.mapper.FriendsMapper;
import com.aiit.friendlink.model.entity.Friends;
import com.aiit.friendlink.model.entity.User;
import com.aiit.friendlink.model.request.FriendAddRequest;
import com.aiit.friendlink.model.vo.FriendsRecordVO;
import com.aiit.friendlink.service.FriendsService;
import com.aiit.friendlink.service.UserService;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aiit.friendlink.contant.FriendConstant.*;
import static com.aiit.friendlink.utils.StringUtils.stringJsonListToLongSet;

/**
 * @author Shuoliu
 * @description 针对表【friends(好友申请管理表)】的数据库操作Service实现
 * @createDate 2023-04-17 09:28:08
 */
@Service
public class FriendsServiceImpl extends ServiceImpl<FriendsMapper, Friends> implements FriendsService {
    @Resource
    private UserService userService;

    @Override
    public boolean addFriendRecords(User loginUser, FriendAddRequest friendAddRequest) {
        if (StringUtils.isNotBlank(friendAddRequest.getRemark()) && friendAddRequest.getRemark().length() > 120) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "申请备注最多120个字符");
        }
        if (ObjectUtils.anyNull(loginUser.getId(), friendAddRequest.getReceiveId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "添加失败");
        }
        // 1.添加的不能是自己
        if (loginUser.getId() == friendAddRequest.getReceiveId()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能添加自己为好友");
        }
        // 2.条数大于等于1 就不能再添加
        LambdaQueryWrapper<Friends> friendsLambdaQueryWrapper = new LambdaQueryWrapper<>();
        friendsLambdaQueryWrapper.eq(Friends::getReceiveId, friendAddRequest.getReceiveId());
        friendsLambdaQueryWrapper.eq(Friends::getFromId, loginUser.getId());
        List<Friends> list = this.list(friendsLambdaQueryWrapper);
        list.forEach(friends -> {
            if (list.size() > 1 && friends.getStatus() == DEFAULT_STATUS) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能重复申请");
            }
        });
        Friends newFriend = new Friends();
        newFriend.setFromId(loginUser.getId());
        newFriend.setReceiveId(friendAddRequest.getReceiveId());
        if (StringUtils.isBlank(friendAddRequest.getRemark())) {
            newFriend.setRemark("我是" + userService.getById(loginUser.getId()).getUsername());
        } else {
            newFriend.setRemark(friendAddRequest.getRemark());
        }
        newFriend.setCreateTime(new Date());
        return this.save(newFriend);
    }

    @Override
    public List<FriendsRecordVO> obtainFriendApplicationRecords(User loginUser) {
        // 查询出当前用户所有申请、同意记录
        LambdaQueryWrapper<Friends> friendsLambdaQueryWrapper = new LambdaQueryWrapper<>();
        friendsLambdaQueryWrapper.eq(Friends::getReceiveId, loginUser.getId());
        return toFriendsVo(friendsLambdaQueryWrapper);
    }

    private List<FriendsRecordVO> toFriendsVo(LambdaQueryWrapper<Friends> friendsLambdaQueryWrapper) {
        List<Friends> friendsList = this.list(friendsLambdaQueryWrapper);
        Collections.reverse(friendsList);
        return friendsList.stream().map(friend -> {
            FriendsRecordVO friendsRecordVO = new FriendsRecordVO();
            BeanUtils.copyProperties(friend, friendsRecordVO);
            User user = userService.getById(friend.getFromId());
            friendsRecordVO.setApplyUser(userService.getSafetyUser(user));
            return friendsRecordVO;
        }).collect(Collectors.toList());
    }

    @Override
    public List<FriendsRecordVO> getMyRecords(User loginUser) {
        // 查询出当前用户所有申请、同意记录
        LambdaQueryWrapper<Friends> myApplyLambdaQueryWrapper = new LambdaQueryWrapper<>();
        myApplyLambdaQueryWrapper.eq(Friends::getFromId, loginUser.getId());
        return toFriendsVo(myApplyLambdaQueryWrapper);
    }

    @Override
    public int getRecordCount(User loginUser) {
        LambdaQueryWrapper<Friends> friendsLambdaQueryWrapper = new LambdaQueryWrapper<>();
        friendsLambdaQueryWrapper.eq(Friends::getReceiveId, loginUser.getId());
        List<Friends> friendsList = this.list(friendsLambdaQueryWrapper);
        int count = 0;
        for (Friends friend : friendsList) {
            if (friend.getStatus() == DEFAULT_STATUS && friend.getIsRead() == NOT_READ) {
                count++;
            }
        }
        return count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toRead(User loginUser, Set<Long> ids) {
        boolean flag = false;
        for (Long id : ids) {
            Friends friend = this.getById(id);
            if (friend.getStatus() == DEFAULT_STATUS && friend.getIsRead() == NOT_READ) {
                friend.setIsRead(READ);
                flag = this.updateById(friend);
            }
        }
        return flag;
    }

    @Override
    public boolean agreeToApply(User loginUser, Long fromId) {
        // 0. 根据receiveId查询所有接收的申请记录
        LambdaQueryWrapper<Friends> friendsLambdaQueryWrapper = new LambdaQueryWrapper<>();
        friendsLambdaQueryWrapper.eq(Friends::getReceiveId, loginUser.getId());
        friendsLambdaQueryWrapper.eq(Friends::getFromId, fromId);
        long recordCount = this.count(friendsLambdaQueryWrapper);
        // 条数小于等于1 就不能再同意
        if (recordCount < 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该申请不存在");
        }
        Friends friend = this.getOne(friendsLambdaQueryWrapper);
        if (DateUtil.between(new Date(), friend.getCreateTime(), DateUnit.DAY) >= 3 || friend.getStatus() == EXPIRED_STATUS) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该申请已过期");
        }
        if (friend.getStatus() == AGREE_STATUS) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该申请已同意");
        }
        // 条数为空才可以同意
        // 1. 分别查询receiveId和fromId的用户，更改userIds中的数据
        User receiveUser = userService.getById(loginUser.getId());
        User fromUser = userService.getById(fromId);
        Set<Long> receiveUserIds = stringJsonListToLongSet(receiveUser.getUserIds());
        Set<Long> fromUserUserIds = stringJsonListToLongSet(fromUser.getUserIds());

        fromUserUserIds.add(receiveUser.getId());
        receiveUserIds.add(fromUser.getId());

        Gson gson = new Gson();
        String jsonFromUserUserIds = gson.toJson(fromUserUserIds);
        String jsonReceiveUserIds = gson.toJson(receiveUserIds);
        receiveUser.setUserIds(jsonReceiveUserIds);
        fromUser.setUserIds(jsonFromUserUserIds);
        // 2. 修改状态由0改为1
        friend.setStatus(AGREE_STATUS);
        return userService.updateById(fromUser) && userService.updateById(receiveUser) && this.updateById(friend);
    }

    @Override
    public boolean canceledApply(Long id, User loginUser) {
        Friends friend = this.getById(id);
        if (friend.getStatus() != DEFAULT_STATUS) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该申请已过期或已通过");
        }
        friend.setStatus(REVOKE_STATUS);
        return this.updateById(friend);
    }
}



